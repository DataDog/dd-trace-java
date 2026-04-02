package datadog.trace.instrumentation.java.lang.jdk21;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.captureActiveSpan;
import static datadog.trace.bootstrap.instrumentation.java.lang.VirtualThreadHelper.VIRTUAL_THREAD_CLASS_NAME;
import static datadog.trace.bootstrap.instrumentation.java.lang.VirtualThreadHelper.VIRTUAL_THREAD_STATE_CLASS_NAME;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.context.Context;
import datadog.environment.JavaVirtualMachine;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.java.lang.VirtualThreadState;
import java.util.Collections;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.Advice.OnMethodEnter;
import net.bytebuddy.asm.Advice.OnMethodExit;

/**
 * Instruments {@code VirtualThread} to propagate the tracing context across mount/unmount cycles
 * using {@link Context#swap()}.
 *
 * <p>The lifecycle is as follows:
 *
 * <ol>
 *   <li>{@code init()}: captures the current {@link Context} and an {@link
 *       datadog.trace.bootstrap.instrumentation.api.AgentScope.Continuation} to prevent the
 *       enclosing trace from completing early.
 *   <li>{@code mount()}: swaps the virtual thread's saved context into the carrier thread, saving
 *       the carrier thread's context.
 *   <li>{@code unmount()}: swaps the carrier thread's original context back, saving the virtual
 *       thread's (possibly modified) context for the next mount.
 *   <li>Steps 2-3 repeat on each park/unpark cycle, potentially on different carrier threads.
 *   <li>{@code afterTerminate()} / {@code afterDone()}: cancels the continuation, releasing the
 *       enclosing trace.
 * </ol>
 *
 * <p>Unlike the previous approach using {@link
 * datadog.trace.bootstrap.instrumentation.java.concurrent.ConcurrentState} which activated/closed
 * individual scopes, this approach swaps the <em>entire scope stack</em> via {@link
 * Context#swap()}. This correctly handles child spans created during virtual thread execution,
 * avoiding out-of-order scope closing.
 *
 * <p>This pattern follows the ZIO {@code FiberContext} and Kotlin {@code
 * DatadogThreadContextElement} instrumentations.
 *
 * <p>Instrumenting the internal {@code VirtualThread.runContinuation()} method does not work as the
 * current thread is still the carrier thread and not a virtual thread. Activating the state when on
 * the carrier thread (ie a platform thread) would store the active context into ThreadLocal using
 * the platform thread as key, making the tracer unable to retrieve the stored context from the
 * current virtual thread (ThreadLocal will not return the value associated to the underlying
 * platform thread as they are considered to be different).
 */
@SuppressWarnings("unused")
@AutoService(InstrumenterModule.class)
public final class VirtualThreadInstrumentation extends InstrumenterModule.ContextTracking
    implements Instrumenter.ForBootstrap, Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public VirtualThreadInstrumentation() {
    super("java-lang", "java-lang-21", "virtual-thread");
  }

  @Override
  public String instrumentedType() {
    return VIRTUAL_THREAD_CLASS_NAME;
  }

  @Override
  public boolean isEnabled() {
    return JavaVirtualMachine.isJavaVersionAtLeast(21) && super.isEnabled();
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap(VIRTUAL_THREAD_CLASS_NAME, VIRTUAL_THREAD_STATE_CLASS_NAME);
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(isConstructor(), getClass().getName() + "$Construct");
    transformer.applyAdvice(isMethod().and(named("mount")), getClass().getName() + "$Activate");
    transformer.applyAdvice(isMethod().and(named("unmount")), getClass().getName() + "$Close");
    transformer.applyAdvice(
        isMethod().and(namedOneOf("afterTerminate", "afterDone")),
        getClass().getName() + "$Terminate");
  }

  public static final class Construct {
    @OnMethodExit(suppress = Throwable.class)
    public static void afterInit(@Advice.This Object virtualThread) {
      Context context = Context.current();
      if (context == Context.root()) {
        return; // no active context to propagate
      }
      VirtualThreadState state = new VirtualThreadState(context, captureActiveSpan());
      InstrumentationContext.get(VIRTUAL_THREAD_CLASS_NAME, VIRTUAL_THREAD_STATE_CLASS_NAME)
          .put(virtualThread, state);
    }
  }

  public static final class Activate {
    @OnMethodExit(suppress = Throwable.class)
    public static void onMount(@Advice.This Object virtualThread) {
      ContextStore<Object, VirtualThreadState> store =
          InstrumentationContext.get(VIRTUAL_THREAD_CLASS_NAME, VIRTUAL_THREAD_STATE_CLASS_NAME);
      VirtualThreadState state = store.get(virtualThread);
      if (state != null) {
        state.onMount();
      }
    }
  }

  public static final class Close {
    @OnMethodEnter(suppress = Throwable.class)
    public static void onUnmount(@Advice.This Object virtualThread) {
      ContextStore<Object, VirtualThreadState> store =
          InstrumentationContext.get(VIRTUAL_THREAD_CLASS_NAME, VIRTUAL_THREAD_STATE_CLASS_NAME);
      VirtualThreadState state = store.get(virtualThread);
      if (state != null) {
        state.onUnmount();
      }
    }
  }

  public static final class Terminate {
    @OnMethodEnter(suppress = Throwable.class)
    public static void onTerminate(@Advice.This Object virtualThread) {
      ContextStore<Object, VirtualThreadState> store =
          InstrumentationContext.get(VIRTUAL_THREAD_CLASS_NAME, VIRTUAL_THREAD_STATE_CLASS_NAME);
      VirtualThreadState state = store.remove(virtualThread);
      if (state != null) {
        state.onTerminate();
      }
    }
  }
}

package datadog.trace.instrumentation.java.lang.jdk21;

import static datadog.context.Context.current;
import static datadog.context.Context.root;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.captureActiveSpan;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.RUNNABLE;
import static datadog.trace.bootstrap.instrumentation.java.lang.VirtualThreadHelper.VIRTUAL_THREAD_CLASS_NAME;
import static datadog.trace.bootstrap.instrumentation.java.lang.VirtualThreadHelper.VIRTUAL_THREAD_STATE_CLASS_NAME;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.context.Context;
import datadog.environment.JavaVirtualMachine;
import datadog.trace.agent.tooling.ExcludeFilterProvider;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope.Continuation;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter;
import datadog.trace.bootstrap.instrumentation.java.lang.VirtualThreadState;
import java.util.Collection;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.Advice.OnMethodEnter;
import net.bytebuddy.asm.Advice.OnMethodExit;

/**
 * Instruments {@code VirtualThread} to propagate the context across mount/unmount cycles using
 * {@link Context#swap()}, and {@link Continuation} to prevent context scope to complete before the
 * thread finishes.
 *
 * <p>The lifecycle is as follows:
 *
 * <ol>
 *   <li>{@code init()}: captures the current {@link Context} and an {@link Continuation} to prevent
 *       the enclosing context scope from completing early.
 *   <li>{@code mount()}: swaps the virtual thread's saved context into the carrier thread, saving
 *       the carrier thread's context.
 *   <li>{@code unmount()}: swaps the carrier thread's original context back, saving the virtual
 *       thread's (possibly modified) context for the next mount.
 *   <li>Steps 2-3 repeat on each park/unpark cycle, potentially on different carrier threads.
 *   <li>{@code afterDone()}: cancels the help continuation, releasing the context scope to be
 *       closed.
 * </ol>
 *
 * <p>Instrumenting the internal {@code VirtualThread.runContinuation()} method does not work as the
 * current thread is still the carrier thread and not a virtual thread. Activating the state when on
 * the carrier thread (ie a platform thread) would store the active context into ThreadLocal using
 * the platform thread as key, making the tracer unable to retrieve the stored context from the
 * current virtual thread (ThreadLocal will not return the value associated to the underlying
 * platform thread as they are considered to be different).
 *
 * @see VirtualThreadState
 */
@SuppressWarnings("unused")
@AutoService(InstrumenterModule.class)
public final class VirtualThreadInstrumentation extends InstrumenterModule.ContextTracking
    implements Instrumenter.ForBootstrap,
        Instrumenter.ForSingleType,
        Instrumenter.HasMethodAdvice,
        ExcludeFilterProvider {

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
  public Map<ExcludeFilter.ExcludeType, ? extends Collection<String>> excludedClasses() {
    // VirtualThread context is activated on mount/unmount, not on Runnable.run().
    return singletonMap(RUNNABLE, singletonList(VIRTUAL_THREAD_CLASS_NAME));
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap(VIRTUAL_THREAD_CLASS_NAME, VIRTUAL_THREAD_STATE_CLASS_NAME);
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(isConstructor(), getClass().getName() + "$Construct");
    transformer.applyAdvice(isMethod().and(named("mount")), getClass().getName() + "$Mount");
    transformer.applyAdvice(isMethod().and(named("unmount")), getClass().getName() + "$Unmount");
    transformer.applyAdvice(
        isMethod().and(named("afterDone")).and(takesArguments(boolean.class)),
        getClass().getName() + "$AfterDone");
  }

  public static final class Construct {
    @OnMethodExit(suppress = Throwable.class)
    public static void afterInit(@Advice.This Object virtualThread) {
      Context context = current();
      if (context == root()) {
        return; // No active context to propagate, avoid creating state
      }
      VirtualThreadState state = new VirtualThreadState(context, captureActiveSpan());
      ContextStore<Object, Object> store =
          InstrumentationContext.get(VIRTUAL_THREAD_CLASS_NAME, VIRTUAL_THREAD_STATE_CLASS_NAME);
      store.put(virtualThread, state);
    }
  }

  public static final class Mount {
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

  public static final class Unmount {
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

  public static final class AfterDone {
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

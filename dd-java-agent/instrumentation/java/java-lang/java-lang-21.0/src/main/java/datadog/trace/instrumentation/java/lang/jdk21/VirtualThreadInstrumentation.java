package datadog.trace.instrumentation.java.lang.jdk21;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ConcurrentState.activateAndContinueContinuation;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ConcurrentState.captureContinuation;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ConcurrentState.closeScope;
import static datadog.trace.bootstrap.instrumentation.java.lang.VirtualThreadHelper.AGENT_SCOPE_CLASS_NAME;
import static datadog.trace.bootstrap.instrumentation.java.lang.VirtualThreadHelper.VIRTUAL_THREAD_CLASS_NAME;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.environment.JavaVirtualMachine;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ConcurrentState;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.Advice.OnMethodEnter;
import net.bytebuddy.asm.Advice.OnMethodExit;

/**
 * Instruments {@code VirtualThread} to capture active state at creation, activate it on mount,
 * close the scope on unmount, and cancel the continuation on thread termination.
 *
 * <p>The lifecycle is as follows:
 *
 * <ol>
 *   <li>{@code init()}: captures and holds a continuation from the active context (span due to
 *       legacy API).
 *   <li>{@code mount()}: activates the held continuation, restoring the context on the current
 *       carrier thread.
 *   <li>{@code unmount()}: closes the scope. The continuation survives as still hold.
 *   <li>Steps 2-3 repeat on each park/unpark cycle, potentially on different carrier threads.
 *   <li>{@code afterTerminate()} (for early versions of JDK 21 and 22 before GA), {@code afterDone}
 *       (for JDK 21 GA above): cancels the held continuation to let the context scope to be closed.
 * </ol>
 *
 * <p>The instrumentation uses two context stores. The first from {@link Runnable} (as {@code
 * VirtualThread} inherits from {@link Runnable}) to store the captured {@link ConcurrentState} to
 * restore later. It additionally stores the {@link AgentScope} to be able to close it later as
 * activation / close is not done around the same method (so passing the scope from {@link
 * OnMethodEnter} / {@link OnMethodExit} using advice return value is not possible).
 *
 * <p>{@link ConcurrentState} is used instead of {@code State} because virtual threads can mount and
 * unmount multiple times across different carrier threads. The held continuation in {@link
 * ConcurrentState} survives multiple activate/close cycles without being consumed, and is
 * explicitly canceled on thread termination.
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
    Map<String, String> contextStore = new HashMap<>();
    contextStore.put(Runnable.class.getName(), ConcurrentState.class.getName());
    contextStore.put(VIRTUAL_THREAD_CLASS_NAME, AGENT_SCOPE_CLASS_NAME);
    return contextStore;
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
    public static void captureScope(@Advice.This Object virtualThread) {
      captureContinuation(
          InstrumentationContext.get(Runnable.class, ConcurrentState.class),
          (Runnable) virtualThread,
          activeSpan());
    }
  }

  public static final class Activate {
    @OnMethodExit(suppress = Throwable.class)
    public static void activate(@Advice.This Object virtualThread) {
      AgentScope scope =
          activateAndContinueContinuation(
              InstrumentationContext.get(Runnable.class, ConcurrentState.class),
              (Runnable) virtualThread);
      ContextStore<Object, AgentScope> scopeStore =
          InstrumentationContext.get(VIRTUAL_THREAD_CLASS_NAME, AGENT_SCOPE_CLASS_NAME);
      scopeStore.put(virtualThread, scope);
    }
  }

  public static final class Close {
    @OnMethodEnter(suppress = Throwable.class)
    public static void close(@Advice.This Object virtualThread) {
      ContextStore<Object, AgentScope> scopeStore =
          InstrumentationContext.get(VIRTUAL_THREAD_CLASS_NAME, AGENT_SCOPE_CLASS_NAME);
      AgentScope scope = scopeStore.remove(virtualThread);
      closeScope(
          InstrumentationContext.get(Runnable.class, ConcurrentState.class),
          (Runnable) virtualThread,
          scope,
          null);
    }
  }

  public static final class Terminate {
    @OnMethodEnter(suppress = Throwable.class)
    public static void terminate(@Advice.This Object virtualThread) {
      ConcurrentState.cancelAndClearContinuation(
          InstrumentationContext.get(Runnable.class, ConcurrentState.class),
          (Runnable) virtualThread);
    }
  }
}

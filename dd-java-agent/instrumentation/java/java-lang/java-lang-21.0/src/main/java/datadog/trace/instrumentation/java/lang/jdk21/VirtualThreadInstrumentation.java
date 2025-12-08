package datadog.trace.instrumentation.java.lang.jdk21;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.capture;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.endTaskScope;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.startTaskScope;
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
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.Advice.OnMethodEnter;
import net.bytebuddy.asm.Advice.OnMethodExit;

/**
 * Instruments {@code VirtualThread} to capture active state at creation, activate it on
 * continuation mount, and close the scope from activation on continuation unmount.
 *
 * <p>The instrumentation uses two context stores. The first from {@link Runnable} (as {@code
 * VirtualThread} inherits from {@link Runnable}) to store the captured {@link State} to restore
 * later. It additionally stores the {@link AgentScope} to be able to close it later as activation /
 * close is not done around the same method (so passing the scope from {@link OnMethodEnter} /
 * {@link OnMethodExit} using advice return value is not possible).
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
public final class VirtualThreadInstrumentation extends InstrumenterModule.Tracing
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
    contextStore.put(Runnable.class.getName(), State.class.getName());
    contextStore.put(VIRTUAL_THREAD_CLASS_NAME, AGENT_SCOPE_CLASS_NAME);
    return contextStore;
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(isConstructor(), getClass().getName() + "$Construct");
    transformer.applyAdvice(isMethod().and(named("mount")), getClass().getName() + "$Activate");
    transformer.applyAdvice(isMethod().and(named("unmount")), getClass().getName() + "$Close");
  }

  public static final class Construct {
    @OnMethodExit(suppress = Throwable.class)
    public static void captureScope(@Advice.This Object virtualThread) {
      capture(InstrumentationContext.get(Runnable.class, State.class), (Runnable) virtualThread);
    }
  }

  public static final class Activate {
    @OnMethodExit(suppress = Throwable.class)
    public static void activate(@Advice.This Object virtualThread) {
      ContextStore<Runnable, State> stateStore =
          InstrumentationContext.get(Runnable.class, State.class);
      ContextStore<Object, Object> scopeStore =
          InstrumentationContext.get(VIRTUAL_THREAD_CLASS_NAME, AGENT_SCOPE_CLASS_NAME);
      AgentScope agentScope = startTaskScope(stateStore, (Runnable) virtualThread);
      scopeStore.put(virtualThread, agentScope);
    }
  }

  public static final class Close {
    @OnMethodEnter(suppress = Throwable.class)
    public static void close(@Advice.This Object virtualThread) {
      ContextStore<Object, Object> scopeStore =
          InstrumentationContext.get(VIRTUAL_THREAD_CLASS_NAME, AGENT_SCOPE_CLASS_NAME);
      Object agentScope = scopeStore.get(virtualThread);
      if (agentScope instanceof AgentScope) {
        endTaskScope((AgentScope) agentScope);
      }
    }
  }
}

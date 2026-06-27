package datadog.trace.instrumentation.java.concurrent.structuredconcurrency25;

import static datadog.trace.bootstrap.InstrumentationContext.get;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.capture;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import net.bytebuddy.asm.Advice.OnMethodExit;
import net.bytebuddy.asm.Advice.This;

/**
 * This instrumentation captures the active span scope at StructuredTaskScope task creation
 * (SubtaskImpl). The scope is then activate and close through the {@link Runnable} instrumentation
 * (SubtaskImpl implementing {@link Runnable}).
 */
@SuppressWarnings("unused")
public class StructuredTaskScope25TaskInstrumentation
    implements Instrumenter.ForBootstrap, Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  @Override
  public String instrumentedType() {
    return "java.util.concurrent.StructuredTaskScopeImpl$SubtaskImpl";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(isConstructor(), getClass().getName() + "$ConstructorAdvice");
  }

  public static final class ConstructorAdvice {
    /**
     * Captures task scope to be restored at the start of VirtualThread.run() method by {@link
     * Runnable} instrumentation.
     *
     * @param subTaskImpl The StructuredTaskScopeImpl.SubtaskImpl object (the advice is compiled
     *     against Java 8 so the type from JDK25 can't be referred, using {@link Object} instead).
     */
    @OnMethodExit(suppress = Throwable.class)
    public static void captureScope(@This Object subTaskImpl) {
      ContextStore<Runnable, State> contextStore = get(Runnable.class, State.class);
      capture(contextStore, (Runnable) subTaskImpl);
    }
  }
}

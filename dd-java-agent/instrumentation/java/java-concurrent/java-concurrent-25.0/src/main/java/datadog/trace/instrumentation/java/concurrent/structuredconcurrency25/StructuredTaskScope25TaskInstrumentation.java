package datadog.trace.instrumentation.java.concurrent.structuredconcurrency25;

import static datadog.trace.bootstrap.InstrumentationContext.get;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.capture;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.SubtaskRegistry.FACTORY;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import datadog.trace.bootstrap.instrumentation.java.concurrent.SubtaskRegistry;
import net.bytebuddy.asm.Advice.Argument;
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
     * Runnable} instrumentation, and registers the subtask in its scope's {@link SubtaskRegistry}
     * to release the continuation at scope close if the subtask never runs.
     *
     * @param subTaskImpl The StructuredTaskScopeImpl.SubtaskImpl object (the advice is compiled
     *     against Java 8 so the type from JDK25 can't be referred, using {@link Object} instead).
     * @param scope The StructuredTaskScopeImpl object owning the subtask (the advice is compiled
     *     against Java 8 so the type from JDK25 can't be referred, using {@link Object} instead).
     */
    @OnMethodExit(suppress = Throwable.class)
    public static void captureScope(@This Object subTaskImpl, @Argument(0) Object scope) {
      ContextStore<Runnable, State> stateStore = get(Runnable.class, State.class);
      Runnable subtask = (Runnable) subTaskImpl;
      capture(stateStore, subtask);
      // Ensure state was captured to before recording for cancellation on scope close
      if (stateStore.get(subtask) != null) {
        ContextStore<Object, SubtaskRegistry> registryStore =
            get(
                "java.util.concurrent.StructuredTaskScopeImpl",
                "datadog.trace.bootstrap.instrumentation.java.concurrent.SubtaskRegistry");
        registryStore.getOrCreate(scope, FACTORY).add(subtask);
      }
    }
  }
}

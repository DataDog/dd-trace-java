package datadog.trace.instrumentation.java.concurrent.structuredconcurrency25;

import static datadog.environment.JavaVirtualMachine.isJavaVersionAtLeast;
import static datadog.trace.bootstrap.InstrumentationContext.get;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.capture;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import net.bytebuddy.asm.Advice.OnMethodExit;
import net.bytebuddy.asm.Advice.This;

// WARNING:
// This instrumentation is tested using smoke tests as instrumented tests cannot run using Java 25.
// Instrumented tests rely on Spock / Groovy which cannot run using Java 25 due to byte-code
// compatibility. Check
// dd-java-agent/instrumentation/java/java-concurrent/java-concurrent-25.0 for this
// instrumentation test suite.

/**
 * This instrumentation captures the active span scope at StructuredTaskScope task creation
 * (SubtaskImpl). The scope is then activate and close through the {@link Runnable} instrumentation
 * (SubtaskImpl implementing {@link Runnable}).
 */
@SuppressWarnings("unused")
@AutoService(InstrumenterModule.class)
public class StructuredTaskScope25Instrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForBootstrap, Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public StructuredTaskScope25Instrumentation() {
    super("java_concurrent", "structured-task-scope", "structured-task-scope-25");
  }

  @Override
  public String instrumentedType() {
    return "java.util.concurrent.StructuredTaskScopeImpl$SubtaskImpl";
  }

  @Override
  public boolean isEnabled() {
    return isJavaVersionAtLeast(25) && super.isEnabled();
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
     * @param subTaskImpl The StructuredTaskScopeImpl.SubtaskImpl object (the advice are compile
     *     against Java 8 so the type from JDK25 can't be referred, using {@link Object} instead
     */
    @OnMethodExit(suppress = Throwable.class)
    public static void captureScope(@This Object subTaskImpl) {
      ContextStore<Runnable, State> contextStore = get(Runnable.class, State.class);
      capture(contextStore, (Runnable) subTaskImpl);
    }
  }
}

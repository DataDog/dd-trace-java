package datadog.trace.instrumentation.java.concurrent.structuredconcurrency21;

import static datadog.environment.JavaVirtualMachine.isJavaVersionBetween;
import static datadog.trace.bootstrap.InstrumentationContext.get;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.capture;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import java.util.Map;
import net.bytebuddy.asm.Advice.OnMethodExit;
import net.bytebuddy.asm.Advice.This;

/**
 * This instrumentation captures the active span scope at StructuredTaskScope task creation
 * (SubtaskImpl). The scope is then activate and close through the {@link Runnable} instrumentation
 * (SubtaskImpl implementing {@link Runnable}).
 */
@SuppressWarnings("unused")
@AutoService(InstrumenterModule.class)
public class StructuredTaskScope21Instrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForBootstrap, Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public StructuredTaskScope21Instrumentation() {
    super("java_concurrent", "structured-task-scope", "structured-task-scope-21");
  }

  @Override
  public String instrumentedType() {
    return "java.util.concurrent.StructuredTaskScope$SubtaskImpl";
  }

  @Override
  public boolean isEnabled() {
    return isJavaVersionBetween(21, 25) && super.isEnabled();
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap(Runnable.class.getName(), State.class.getName());
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
     * @param subTaskImpl The StructuredTaskScope.SubtaskImpl object (the advice are compile against
     *     Java 8 so the type from JDK25 can't be referred, using {@link Object} instead
     */
    @OnMethodExit(suppress = Throwable.class)
    public static void captureScope(@This Object subTaskImpl) {
      ContextStore<Runnable, State> contextStore = get(Runnable.class, State.class);
      capture(contextStore, (Runnable) subTaskImpl);
    }
  }
}

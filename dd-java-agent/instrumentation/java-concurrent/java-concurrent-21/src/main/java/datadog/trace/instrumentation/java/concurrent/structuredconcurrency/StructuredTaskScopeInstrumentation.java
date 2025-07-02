package datadog.trace.instrumentation.java.concurrent.structuredconcurrency;

import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.capture;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import com.google.auto.service.AutoService;
import datadog.environment.JavaVirtualMachine;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import java.util.Map;
import net.bytebuddy.asm.Advice;

/**
 * This instrumentation captures the active span scope at StructuredTaskScope task creation
 * (SubtaskImpl). The scope is then activate and close through the Runnable instrumentation
 * (SubtaskImpl implementation Runnable).
 */
@SuppressWarnings("unused")
@AutoService(InstrumenterModule.class)
public class StructuredTaskScopeInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForBootstrap, Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public StructuredTaskScopeInstrumentation() {
    super("java_concurrent", "structured_task_scope");
  }

  @Override
  public String instrumentedType() {
    return "java.util.concurrent.StructuredTaskScope$SubtaskImpl";
  }

  @Override
  public boolean isEnabled() {
    return JavaVirtualMachine.isJavaVersionAtLeast(21) && super.isEnabled();
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap(
        "java.util.concurrent.StructuredTaskScope.SubtaskImpl", State.class.getName());
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(isConstructor(), getClass().getName() + "$ConstructorAdvice");
  }

  public static final class ConstructorAdvice {
    @Advice.OnMethodExit
    public static <T> void captureScope(
        @Advice.This Object task // StructuredTaskScope.SubtaskImpl (can't use the type)
        ) {
      ContextStore<Object, State> contextStore =
          InstrumentationContext.get(
              "java.util.concurrent.StructuredTaskScope.SubtaskImpl",
              "datadog.trace.bootstrap.instrumentation.java.concurrent.State");
      capture(contextStore, task);
    }
  }
}

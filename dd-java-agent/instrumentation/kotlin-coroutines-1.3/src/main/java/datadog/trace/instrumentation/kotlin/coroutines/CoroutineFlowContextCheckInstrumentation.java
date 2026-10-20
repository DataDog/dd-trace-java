package datadog.trace.instrumentation.kotlin.coroutines;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import datadog.trace.agent.tooling.Instrumenter;
import kotlin.coroutines.CoroutineContext;
import net.bytebuddy.asm.Advice;

/** Keeps tracing state transparent to Kotlin's flow context invariant. */
public class CoroutineFlowContextCheckInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  @Override
  public String instrumentedType() {
    return "kotlinx.coroutines.flow.internal.SafeCollector";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("checkContext").and(takesArgument(0, named("kotlin.coroutines.CoroutineContext"))),
        getClass().getName() + "$ContextCheckAdvice");
  }

  public static class ContextCheckAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter(
        @Advice.FieldValue("collectContext") CoroutineContext collected,
        @Advice.Argument(value = 0, readOnly = false) CoroutineContext emitted) {
      emitted = DatadogThreadContextElement.forFlowContextCheck(emitted, collected);
    }
  }
}

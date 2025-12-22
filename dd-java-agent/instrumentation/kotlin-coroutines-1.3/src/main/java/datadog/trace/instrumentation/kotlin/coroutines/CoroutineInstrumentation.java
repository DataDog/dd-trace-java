package datadog.trace.instrumentation.kotlin.coroutines;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import datadog.trace.agent.tooling.Instrumenter;
import kotlinx.coroutines.AbstractCoroutine;
import net.bytebuddy.asm.Advice;

/**
 * Captures the Datadog context when non-lazy coroutines are constructed. Also cancels the captured
 * Datadog context when any coroutine completes (regardless whether it is lazy or non-lazy).
 */
public class CoroutineInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  @Override
  public String instrumentedType() {
    return "kotlinx.coroutines.AbstractCoroutine";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isConstructor(), CoroutineInstrumentation.class.getName() + "$ConstructorAdvice");
    transformer.applyAdvice(
        named("onCompletionInternal"),
        CoroutineInstrumentation.class.getName() + "$OnCompletionAdvice");
  }

  public static class ConstructorAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterConstruction(@Advice.This AbstractCoroutine<?> coroutine) {
      if (coroutine.isActive()) {
        DatadogThreadContextElement.captureDatadogContext(coroutine);
      }
    }
  }

  public static class OnCompletionAdvice {
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void afterCompletion(@Advice.This AbstractCoroutine<?> coroutine) {
      DatadogThreadContextElement.cancelDatadogContext(coroutine);
    }
  }
}

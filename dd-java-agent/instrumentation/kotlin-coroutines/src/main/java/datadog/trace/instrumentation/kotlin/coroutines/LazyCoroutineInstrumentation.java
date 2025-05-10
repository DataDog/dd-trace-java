package datadog.trace.instrumentation.kotlin.coroutines;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import datadog.trace.agent.tooling.Instrumenter;
import kotlinx.coroutines.AbstractCoroutine;
import net.bytebuddy.asm.Advice;

/** Captures the Datadog context when lazy coroutines start. */
public class LazyCoroutineInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  @Override
  public String instrumentedType() {
    return "kotlinx.coroutines.LazyDeferredCoroutine";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("onStart")),
        LazyCoroutineInstrumentation.class.getName() + "$OnStartAdvice");
  }

  public static class OnStartAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onStart(@Advice.This AbstractCoroutine<?> coroutine) {
      DatadogThreadContextElement.captureDatadogContext(coroutine);
    }
  }
}

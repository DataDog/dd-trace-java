package datadog.trace.instrumentation.resilience4j;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import datadog.trace.agent.tooling.Instrumenter;
import io.github.resilience4j.core.functions.CheckedSupplier;
import java.util.concurrent.Callable;
import net.bytebuddy.asm.Advice;

public class FallbackCallableInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  @Override
  public String instrumentedType() {
    return "io.github.resilience4j.decorators.Decorators$DecorateCallable";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("withFallback")),
        FallbackCallableInstrumentation.class.getName() + "$CallableAdvice");
  }

  public static class CallableAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterExecute(
        @Advice.FieldValue(value = "callable", readOnly = false) Callable<?> callable) {
      callable =
          new WrapperWithContext.CallableWithContext<>(
              callable, Resilience4jSpanDecorator.DECORATE, null);
    }

    // 2.0.0+
    public static void muzzleCheck(CheckedSupplier<?> cs) throws Throwable {
      cs.get();
    }
  }
}

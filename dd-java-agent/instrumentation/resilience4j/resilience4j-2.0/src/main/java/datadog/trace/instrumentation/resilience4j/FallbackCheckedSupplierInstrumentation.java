package datadog.trace.instrumentation.resilience4j;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import datadog.trace.agent.tooling.Instrumenter;
import io.github.resilience4j.core.functions.CheckedSupplier;
import net.bytebuddy.asm.Advice;

public class FallbackCheckedSupplierInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  @Override
  public String instrumentedType() {
    return "io.github.resilience4j.decorators.Decorators$DecorateCheckedSupplier";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("withFallback")),
        FallbackCheckedSupplierInstrumentation.class.getName() + "$CheckedSupplierAdvice");
  }

  public static class CheckedSupplierAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterExecute(
        @Advice.FieldValue(value = "supplier", readOnly = false) CheckedSupplier<?> supplier) {
      supplier =
          new WrapperWithContext.CheckedSupplierWithContext<>(
              supplier, Resilience4jSpanDecorator.DECORATE, null);
    }
  }
}

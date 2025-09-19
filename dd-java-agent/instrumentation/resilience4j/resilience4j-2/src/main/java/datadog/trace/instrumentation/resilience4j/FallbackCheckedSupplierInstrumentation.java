package datadog.trace.instrumentation.resilience4j;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.InstrumenterModule;
import io.github.resilience4j.core.functions.CheckedSupplier;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class FallbackCheckedSupplierInstrumentation extends Resilience4jInstrumentation {
  public FallbackCheckedSupplierInstrumentation() {
    super("resilience4j-fallback");
  }

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

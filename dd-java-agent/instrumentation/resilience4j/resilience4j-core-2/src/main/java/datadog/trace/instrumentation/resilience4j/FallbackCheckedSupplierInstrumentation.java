package datadog.trace.instrumentation.resilience4j;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.returns;

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
    return "io.github.resilience4j.core.CheckedFunctionUtils";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(namedOneOf("recover", "andThen")).and(returns(named(CHECKED_SUPPLIER_FQCN))),
        FallbackCheckedSupplierInstrumentation.class.getName() + "$CheckedSupplierAdvice");
  }

  public static class CheckedSupplierAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterExecute(@Advice.Return(readOnly = false) CheckedSupplier<?> outbound) {
      outbound =
          new WrapperWithContext.CheckedSupplierWithContext<>(
              outbound, Resilience4jSpanDecorator.DECORATE, null);
    }
  }
}

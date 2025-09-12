package datadog.trace.instrumentation.resilience4j;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.returns;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.function.Supplier;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class FallbackSupplierInstrumentation extends Resilience4jInstrumentation {
  public FallbackSupplierInstrumentation() {
    super("resilience4j-fallback");
  }

  @Override
  public String instrumentedType() {
    return "io.github.resilience4j.core.SupplierUtils";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(namedOneOf("recover", "andThen")).and(returns(named(SUPPLIER_FQCN))),
        FallbackSupplierInstrumentation.class.getName() + "$SupplierAdvice");
  }

  public static class SupplierAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterExecute(@Advice.Return(readOnly = false) Supplier<?> outbound) {
      outbound =
          new WrapperWithContext.SupplierWithContext<>(
              outbound, Resilience4jSpanDecorator.DECORATE, null);
    }
  }
}

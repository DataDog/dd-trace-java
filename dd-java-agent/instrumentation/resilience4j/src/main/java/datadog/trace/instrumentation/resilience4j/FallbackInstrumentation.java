package datadog.trace.instrumentation.resilience4j;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.function.Supplier;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class FallbackInstrumentation extends AbstractResilience4jInstrumentation {

  private static final String SUPPLIER_UTILS_FQCN = "io.github.resilience4j.core.SupplierUtils";

  public FallbackInstrumentation() {
    super("resilience4j-fallback");
  }

  @Override
  public String instrumentedType() {
    return SUPPLIER_UTILS_FQCN;
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("recover"))
            .and(takesArgument(0, named("java.util.function.Supplier")))
            .and(returns(named("java.util.function.Supplier"))),
        FallbackInstrumentation.class.getName() + "$SupplierAdvice");
  }

  public static class SupplierAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterExecute(@Advice.Return(readOnly = false) Supplier<?> supplier) {
      supplier = DDContext.ofFallback().tracedSupplier(supplier);
    }
  }
}

package datadog.trace.instrumentation.resilience4j;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.function.Supplier;

public abstract class Resilience4jInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public static final String CHECKED_SUPPLIER_FQCN =
      "io.github.resilience4j.core.functions.CheckedSupplier";
  public static final String SUPPLIER_FQCN = Supplier.class.getName();

  public Resilience4jInstrumentation(String... additionalNames) {
    super("resilience4j-core", additionalNames);
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".ContextHolder",
      packageName + ".ContextHolder$CheckedSupplierWithContext",
      packageName + ".ContextHolder$SupplierCompletionStageWithContext",
      packageName + ".ContextHolder$SupplierWithContext",
      packageName + ".AbstractResilience4jDecorator",
      packageName + ".NoopDecorator",
      packageName + ".ActiveResilience4jSpan",
      packageName + ".CircuitBreakerDecorator",
      packageName + ".RetryDecorator",
    };
  }
}

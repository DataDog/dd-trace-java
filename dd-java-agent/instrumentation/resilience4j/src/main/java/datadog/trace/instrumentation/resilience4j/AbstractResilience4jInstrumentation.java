package datadog.trace.instrumentation.resilience4j;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.function.Supplier;

public abstract class AbstractResilience4jInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public static final String CHECKED_SUPPLIER_FQCN =
      "io.github.resilience4j.core.functions.CheckedSupplier";
  public static final String SUPPLIER_FQCN = Supplier.class.getName();

  public AbstractResilience4jInstrumentation(String... additionalNames) {
    super("resilience4j", additionalNames);
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".DDContext",
      packageName + ".CheckedSupplierWithContext",
      packageName + ".SupplierWithContext",
    };
  }
}

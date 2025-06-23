package datadog.trace.instrumentation.resilience4j;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;

public abstract class AbstractResilience4jInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public AbstractResilience4jInstrumentation(String... additionalNames) {
    super("resilience4j", additionalNames);
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".DDContext", packageName + ".CheckedSupplierWithContext",
    };
  }
}

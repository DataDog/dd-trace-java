package datadog.trace.instrumentation.resilience4j;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;

public abstract class Resilience4jReactorInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public Resilience4jReactorInstrumentation(String... additionalNames) {
    super("resilience4j-reactor", additionalNames);
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".Resilience4jSpan",
      packageName + ".Resilience4jSpanDecorator",
      packageName + ".CircuitBreakerDecorator",
      packageName + ".RetryDecorator",
      packageName + ".ReactorHelper",
    };
  }
}

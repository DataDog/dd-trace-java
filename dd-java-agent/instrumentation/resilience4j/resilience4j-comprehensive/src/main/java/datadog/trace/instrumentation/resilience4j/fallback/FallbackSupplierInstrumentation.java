package datadog.trace.instrumentation.resilience4j.fallback;

import datadog.trace.agent.tooling.Instrumenter;

public final class FallbackSupplierInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  @Override
  public String instrumentedType() {
    return "io.github.resilience4j.decorators.Decorators$DecorateSupplier";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    // Fallback instrumentation for Supplier
    // TODO: Implement fallback decorator instrumentation
  }
}

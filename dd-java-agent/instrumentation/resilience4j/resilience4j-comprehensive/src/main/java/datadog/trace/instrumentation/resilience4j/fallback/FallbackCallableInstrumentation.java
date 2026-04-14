package datadog.trace.instrumentation.resilience4j.fallback;

import datadog.trace.agent.tooling.Instrumenter;

public final class FallbackCallableInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  @Override
  public String instrumentedType() {
    return "io.github.resilience4j.decorators.Decorators$DecorateCallable";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    // Fallback instrumentation for Callable
    // TODO: Implement fallback decorator instrumentation
  }
}

package datadog.trace.instrumentation.resilience4j.fallback;

import datadog.trace.agent.tooling.Instrumenter;

public final class FallbackCompletionStageInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  @Override
  public String instrumentedType() {
    return "io.github.resilience4j.decorators.Decorators$DecorateCompletionStage";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    // Fallback instrumentation for CompletionStage
    // TODO: Implement fallback decorator instrumentation
  }
}

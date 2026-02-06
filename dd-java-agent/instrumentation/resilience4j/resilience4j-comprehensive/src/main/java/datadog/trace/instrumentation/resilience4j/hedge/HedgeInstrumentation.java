package datadog.trace.instrumentation.resilience4j.hedge;

import datadog.trace.agent.tooling.Instrumenter;

public final class HedgeInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  @Override
  public String instrumentedType() {
    return "io.github.resilience4j.hedge.Hedge";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    // Hedge instrumentation requires async handling
    // TODO: Implement hedge decorator instrumentation
  }
}

package datadog.trace.instrumentation.resilience4j.cache;

import datadog.trace.agent.tooling.Instrumenter;

public final class CacheInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  @Override
  public String instrumentedType() {
    return "io.github.resilience4j.cache.Cache";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    // Cache instrumentation requires special handling due to JCache integration
    // TODO: Implement cache decorator instrumentation
  }
}

package datadog.trace.instrumentation.ignite.v2.cache;

import datadog.trace.agent.tooling.Instrumenter;

public abstract class AbstractIgniteCacheInstrumentation
    implements Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "org.apache.ignite.internal.processors.cache.IgniteCacheProxy",
      "org.apache.ignite.internal.processors.cache.IgniteCacheProxyImpl",
      "org.apache.ignite.internal.processors.cache.GatewayProtectedCacheProxy",
      "org.apache.ignite.IgniteCache"
    };
  }
}

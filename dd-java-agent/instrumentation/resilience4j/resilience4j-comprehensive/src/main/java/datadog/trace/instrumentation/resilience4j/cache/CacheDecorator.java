package datadog.trace.instrumentation.resilience4j.cache;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.instrumentation.resilience4j.common.Resilience4jSpanDecorator;
import io.github.resilience4j.cache.Cache;

public final class CacheDecorator extends Resilience4jSpanDecorator<Cache<?>> {
  public static final CacheDecorator DECORATE = new CacheDecorator();
  public static final String TAG_PREFIX = "resilience4j.cache.";
  public static final String TAG_METRICS_PREFIX = TAG_PREFIX + "metrics.";

  private CacheDecorator() {
    super();
  }

  @Override
  public void decorate(AgentSpan span, Cache<?> data) {
    span.setTag(TAG_PREFIX + "name", data.getName());
    if (Config.get().isResilience4jTagMetricsEnabled()) {
      Cache.Metrics metrics = data.getMetrics();
      span.setTag(TAG_METRICS_PREFIX + "hits", metrics.getNumberOfCacheHits());
      span.setTag(TAG_METRICS_PREFIX + "misses", metrics.getNumberOfCacheMisses());
    }
  }
}

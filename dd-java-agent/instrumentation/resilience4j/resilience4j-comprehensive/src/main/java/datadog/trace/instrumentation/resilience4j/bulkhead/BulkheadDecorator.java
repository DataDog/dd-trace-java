package datadog.trace.instrumentation.resilience4j.bulkhead;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.instrumentation.resilience4j.common.Resilience4jSpanDecorator;
import io.github.resilience4j.bulkhead.Bulkhead;

public final class BulkheadDecorator extends Resilience4jSpanDecorator<Bulkhead> {
  public static final BulkheadDecorator DECORATE = new BulkheadDecorator();
  public static final String TAG_PREFIX = "resilience4j.bulkhead.";
  public static final String TAG_METRICS_PREFIX = TAG_PREFIX + "metrics.";

  private BulkheadDecorator() {
    super();
  }

  @Override
  public void decorate(AgentSpan span, Bulkhead data) {
    span.setTag(TAG_PREFIX + "name", data.getName());
    span.setTag(TAG_PREFIX + "type", "semaphore");
    if (Config.get().isResilience4jTagMetricsEnabled()) {
      Bulkhead.Metrics metrics = data.getMetrics();
      span.setTag(TAG_METRICS_PREFIX + "available_concurrent_calls", metrics.getAvailableConcurrentCalls());
      span.setTag(TAG_METRICS_PREFIX + "max_allowed_concurrent_calls", metrics.getMaxAllowedConcurrentCalls());
    }
  }
}

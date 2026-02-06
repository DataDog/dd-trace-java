package datadog.trace.instrumentation.resilience4j.bulkhead;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.instrumentation.resilience4j.common.Resilience4jSpanDecorator;
import io.github.resilience4j.bulkhead.ThreadPoolBulkhead;

public final class ThreadPoolBulkheadDecorator extends Resilience4jSpanDecorator<ThreadPoolBulkhead> {
  public static final ThreadPoolBulkheadDecorator DECORATE = new ThreadPoolBulkheadDecorator();
  public static final String TAG_PREFIX = "resilience4j.bulkhead.";
  public static final String TAG_METRICS_PREFIX = TAG_PREFIX + "metrics.";

  private ThreadPoolBulkheadDecorator() {
    super();
  }

  @Override
  public void decorate(AgentSpan span, ThreadPoolBulkhead data) {
    span.setTag(TAG_PREFIX + "name", data.getName());
    span.setTag(TAG_PREFIX + "type", "threadpool");
    if (Config.get().isResilience4jTagMetricsEnabled()) {
      ThreadPoolBulkhead.Metrics metrics = data.getMetrics();
      span.setTag(TAG_METRICS_PREFIX + "queue_depth", metrics.getQueueDepth());
      span.setTag(TAG_METRICS_PREFIX + "queue_capacity", metrics.getQueueCapacity());
      span.setTag(TAG_METRICS_PREFIX + "thread_pool_size", metrics.getThreadPoolSize());
      span.setTag(TAG_METRICS_PREFIX + "remaining_queue_capacity", metrics.getRemainingQueueCapacity());
    }
  }
}

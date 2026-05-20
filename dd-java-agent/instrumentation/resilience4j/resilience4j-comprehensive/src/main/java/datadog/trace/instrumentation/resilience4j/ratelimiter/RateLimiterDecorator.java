package datadog.trace.instrumentation.resilience4j.ratelimiter;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.instrumentation.resilience4j.common.Resilience4jSpanDecorator;
import io.github.resilience4j.ratelimiter.RateLimiter;

public final class RateLimiterDecorator extends Resilience4jSpanDecorator<RateLimiter> {
  public static final RateLimiterDecorator DECORATE = new RateLimiterDecorator();
  public static final String TAG_PREFIX = "resilience4j.rate_limiter.";
  public static final String TAG_METRICS_PREFIX = TAG_PREFIX + "metrics.";

  private RateLimiterDecorator() {
    super();
  }

  @Override
  public void decorate(AgentSpan span, RateLimiter data) {
    span.setTag(TAG_PREFIX + "name", data.getName());
    RateLimiter.Metrics metrics = data.getMetrics();
    if (Config.get().isResilience4jTagMetricsEnabled()) {
      span.setTag(TAG_METRICS_PREFIX + "available_permissions", metrics.getAvailablePermissions());
      span.setTag(TAG_METRICS_PREFIX + "number_of_waiting_threads", metrics.getNumberOfWaitingThreads());
    }
  }
}

package datadog.trace.instrumentation.resilience4j;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.github.resilience4j.retry.Retry;

public final class RetryDecorator extends Resilience4jSpanDecorator<Retry> {
  public static final RetryDecorator DECORATE = new RetryDecorator();
  public static final String TAG_PREFIX = "resilience4j.retry.";
  public static final String TAG_METRICS_PREFIX = TAG_PREFIX + "metrics.";

  private RetryDecorator() {
    super();
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"resilience4j.retry"};
  }

  @Override
  public void decorate(AgentSpan span, Retry data) {
    span.setTag(TAG_PREFIX + "name", data.getName());
    span.setTag(TAG_PREFIX + "max_attempts", data.getRetryConfig().getMaxAttempts());
    span.setTag(
        TAG_PREFIX + "fail_after_max_attempts", data.getRetryConfig().isFailAfterMaxAttempts());
    if (Config.get().isResilience4jTagMetricsEnabled()) {
      Retry.Metrics ms = data.getMetrics();
      span.setTag(
          TAG_METRICS_PREFIX + "success_without_retry",
          ms.getNumberOfSuccessfulCallsWithoutRetryAttempt());
      span.setTag(
          TAG_METRICS_PREFIX + "failed_without_retry",
          ms.getNumberOfFailedCallsWithoutRetryAttempt());
      span.setTag(
          TAG_METRICS_PREFIX + "success_with_retry",
          ms.getNumberOfSuccessfulCallsWithRetryAttempt());
      span.setTag(
          TAG_METRICS_PREFIX + "failed_with_retry", ms.getNumberOfFailedCallsWithRetryAttempt());
    }
  }
}

package datadog.trace.instrumentation.resilience4j;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.github.resilience4j.retry.Retry;

public final class RetryDecorator extends AbstractResilience4jDecorator<Retry> {
  public static final RetryDecorator DECORATE = new RetryDecorator();

  private RetryDecorator() {
    super();
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"resilience4j.retry"};
  }

  @Override
  public void decorate(AgentSpan span, Retry data) {
    //    span.setSpanName("resilience4j.retry");
    //    span.setResourceName(data.getName());

    span.setTag("resilience4j.retry.name", data.getName());
    span.setTag("resilience4j.retry.max_attempts", data.getRetryConfig().getMaxAttempts());
    span.setTag(
        "resilience4j.retry.fail_after_max_attempts",
        data.getRetryConfig().isFailAfterMaxAttempts());

    Retry.Metrics ms = data.getMetrics();
    span.setTag(
        "resilience4j.retry.metrics.success_without_retry",
        ms.getNumberOfSuccessfulCallsWithoutRetryAttempt());
    span.setTag(
        "resilience4j.retry.metrics.failed_without_retry",
        ms.getNumberOfFailedCallsWithoutRetryAttempt());
    span.setTag(
        "resilience4j.retry.metrics.success_with_retry",
        ms.getNumberOfSuccessfulCallsWithRetryAttempt());
    span.setTag(
        "resilience4j.retry.metrics.failed_with_retry",
        ms.getNumberOfFailedCallsWithRetryAttempt());
  }
}

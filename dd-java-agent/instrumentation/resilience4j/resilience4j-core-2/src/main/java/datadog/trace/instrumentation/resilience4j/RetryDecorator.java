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
    span.setTag("resilience4j.retry.name", data.getName());
    //    span.setTag("resilience4j.retry.number_of_successful_calls_with_retry_attempt",
    // data.getMetrics().getNumberOfSuccessfulCallsWithRetryAttempt());
    //    span.setTag("resilience4j.retry.number_of_failed_calls_with_retry_attempt",
    // data.getMetrics().getNumberOfFailedCallsWithRetryAttempt());
    //    span.setTag("resilience4j.retry.number_of_successful_calls_without_retry_attempt",
    // data.getMetrics().getNumberOfSuccessfulCallsWithoutRetryAttempt());
    //    span.setTag("resilience4j.retry.number_of_failed_calls_without_retry_attempt",
    // data.getMetrics().getNumberOfFailedCallsWithoutRetryAttempt());
    // TODO
  }
}

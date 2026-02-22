package datadog.trace.instrumentation.resilience4j.retry;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.instrumentation.resilience4j.common.Resilience4jSpanDecorator;
import io.github.resilience4j.retry.Retry;

public final class RetryDecorator extends Resilience4jSpanDecorator<Retry> {
  public static final RetryDecorator DECORATE = new RetryDecorator();
  public static final String TAG_PREFIX = "resilience4j.retry.";

  private RetryDecorator() {
    super();
  }

  @Override
  public void decorate(AgentSpan span, Retry data) {
    span.setTag(TAG_PREFIX + "name", data.getName());
    span.setTag(TAG_PREFIX + "max_attempts", data.getRetryConfig().getMaxAttempts());
  }
}

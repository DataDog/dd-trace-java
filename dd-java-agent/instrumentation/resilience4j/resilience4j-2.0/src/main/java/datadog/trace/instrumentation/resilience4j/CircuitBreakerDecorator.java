package datadog.trace.instrumentation.resilience4j;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;

public final class CircuitBreakerDecorator extends Resilience4jSpanDecorator<CircuitBreaker> {
  public static final CircuitBreakerDecorator DECORATE = new CircuitBreakerDecorator();
  public static final String TAG_PREFIX = "resilience4j.circuit_breaker.";
  public static final String TAG_METRICS_PREFIX = TAG_PREFIX + "metrics.";

  private CircuitBreakerDecorator() {
    super();
  }

  @Override
  public void decorate(AgentSpan span, CircuitBreaker data) {
    span.setTag(TAG_PREFIX + "name", data.getName());
    span.setTag(TAG_PREFIX + "state", data.getState().toString());
    if (Config.get().isResilience4jTagMetricsEnabled()) {
      CircuitBreaker.Metrics ms = data.getMetrics();
      span.setTag(TAG_METRICS_PREFIX + "failure_rate", ms.getFailureRate());
      span.setTag(TAG_METRICS_PREFIX + "slow_call_rate", ms.getSlowCallRate());
      span.setTag(TAG_METRICS_PREFIX + "slow_calls", ms.getNumberOfSlowCalls());
      span.setTag(
          TAG_METRICS_PREFIX + "slow_successful_calls", ms.getNumberOfSlowSuccessfulCalls());
      span.setTag(TAG_METRICS_PREFIX + "slow_failed_calls", ms.getNumberOfSlowFailedCalls());
      span.setTag(TAG_METRICS_PREFIX + "buffered_calls", ms.getNumberOfBufferedCalls());
      span.setTag(TAG_METRICS_PREFIX + "failed_calls", ms.getNumberOfFailedCalls());
      span.setTag(TAG_METRICS_PREFIX + "not_permitted_calls", ms.getNumberOfNotPermittedCalls());
      span.setTag(TAG_METRICS_PREFIX + "successful_calls", ms.getNumberOfSuccessfulCalls());
    }
  }
}

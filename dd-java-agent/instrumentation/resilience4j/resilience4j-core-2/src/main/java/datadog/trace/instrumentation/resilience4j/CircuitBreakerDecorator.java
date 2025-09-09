package datadog.trace.instrumentation.resilience4j;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;

public final class CircuitBreakerDecorator extends AbstractResilience4jDecorator<CircuitBreaker> {
  public static final CircuitBreakerDecorator DECORATE = new CircuitBreakerDecorator();

  private CircuitBreakerDecorator() {
    super();
  }

  @Override
  public void decorate(AgentSpan span, CircuitBreaker data) {
    //    span.setSpanName("resilience4j.circuit-breaker");
    //    span.setResourceName(data.getName());

    span.setTag("resilience4j.circuit_breaker.name", data.getName());
    span.setTag("resilience4j.circuit_breaker.state", data.getState().toString());

    CircuitBreaker.Metrics ms = data.getMetrics();
    span.setTag("resilience4j.circuit-breaker.metrics.failure_rate", ms.getFailureRate());
    span.setTag("resilience4j.circuit-breaker.metrics.slow_call_rate", ms.getSlowCallRate());
    span.setTag(
        "resilience4j.circuit-breaker.metrics.number_of_slow_calls", ms.getNumberOfSlowCalls());
    span.setTag(
        "resilience4j.circuit-breaker.metrics.number_of_slow_successful_calls",
        ms.getNumberOfSlowSuccessfulCalls());
    span.setTag(
        "resilience4j.circuit-breaker.metrics.number_of_slow_failed_calls",
        ms.getNumberOfSlowFailedCalls());
    span.setTag(
        "resilience4j.circuit-breaker.metrics.number_of_buffered_calls",
        ms.getNumberOfBufferedCalls());
    span.setTag(
        "resilience4j.circuit-breaker.metrics.number_of_failed_calls", ms.getNumberOfFailedCalls());
    span.setTag(
        "resilience4j.circuit-breaker.metrics.number_of_not_permitted_calls",
        ms.getNumberOfNotPermittedCalls());
    span.setTag(
        "resilience4j.circuit-breaker.metrics.number_of_successful_calls",
        ms.getNumberOfSuccessfulCalls());
  }
}

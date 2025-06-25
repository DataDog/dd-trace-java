package datadog.trace.instrumentation.resilience4j;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;

public final class CircuitBreakerDecorator extends AbstractResilience4jDecorator<CircuitBreaker> {
  public static final CircuitBreakerDecorator DECORATE = new CircuitBreakerDecorator();

  private CircuitBreakerDecorator() {
    super();
  }

  @Override
  protected void decorate(AgentScope scope, CircuitBreaker data) {
    // TODO
    scope
        .span()
        .setTag("resilience4j.circuit_breaker.name", data.getName())
        .setTag("resilience4j.circuit_breaker.state", data.getState().toString());
    //        .setTag("resilience4j.circuit_breaker.failure_rate_threshold",
    // data.getFailureRateThreshold())
    //        .setTag("resilience4j.circuit_breaker.wait_duration_in_open_state",
    // data.getWaitDurationInOpenState())
    //        .setTag("resilience4j.circuit_breaker.sliding_window_size",
    // data.getSlidingWindowSize())
    //        .setTag("resilience4j.circuit_breaker.permitted_number_of_calls_in_half_open_state",
    //            data.getPermittedNumberOfCallsInHalfOpenState());
  }
}

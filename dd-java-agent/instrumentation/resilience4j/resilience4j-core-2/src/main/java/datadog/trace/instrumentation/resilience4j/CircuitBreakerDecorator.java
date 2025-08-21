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
    // TODO
    span.setSpanName(data.getName());
    span.setTag("resilience4j.circuit_breaker.name", data.getName())
        .setTag("resilience4j.circuit_breaker.state", data.getState().toString());
  }
}

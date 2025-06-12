package datadog.trace.instrumentation.resilience4j;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class CircuitBreakerWithContext implements CircuitBreaker {
  private final CircuitBreaker cb;

  private AgentSpan span;
  private AgentScope scope;

  public CircuitBreakerWithContext(CircuitBreaker cb) {
    this.cb = cb;
  }

  private void ddStartScope() {
    span = AgentTracer.startSpan("resilience4j", "resilience4j");
    scope = AgentTracer.activateSpan(span);

    //    AgentSpan parent = AgentTracer.activeSpan();
    //    AgentSpanContext parentContext =
    //        parent != null ? parent.context() : AgentTracer.noopSpanContext();

    //    if (parent == null || !parent.getSpanName().equals("resilience4j")) {
    //      span = AgentTracer.startSpan("resilience4j", "resilience4j", parentContext);
  }

  public void ddCloseScope() {
    System.err.println(">> ddCloseScope " + Thread.currentThread().getName());
    if (scope != null) {
      scope.close();
      scope = null;
    }
  }

  private void finishSpan(Throwable error) {
    if (span != null) {
      // TODO set error tag
      span.finish();
      span = null;
    }
  }

  @Override
  public boolean tryAcquirePermission() {
    if (!cb.tryAcquirePermission()) {
      // TODO do we want to record non-permitted attempt?
      return false;
    }

    ddStartScope();
    return true;
  }

  @Override
  public void acquirePermission() {
    cb.acquirePermission(); // TODO do we want to record non-permitted attempt, then need to catch

    ddStartScope();
  }

  @Override
  public void releasePermission() {
    System.err.println("releasePermission");
    // TODO should close the scope and finish the span?

    cb.releasePermission();
  }

  @Override
  public void onError(long duration, TimeUnit durationUnit, Throwable throwable) {
    ddCloseScope();
    finishSpan(throwable);
    cb.onError(duration, durationUnit, throwable);
  }

  @Override
  public void onSuccess(long duration, TimeUnit durationUnit) {
    ddCloseScope();
    finishSpan(null);
    cb.onSuccess(duration, durationUnit);
  }

  @Override
  public void onResult(long duration, TimeUnit durationUnit, Object result) {
    ddCloseScope();
    finishSpan(null);
    cb.onResult(duration, durationUnit, result);
  }

  @Override
  public void reset() {
    cb.reset();
  }

  @Override
  public void transitionToClosedState() {
    cb.transitionToClosedState();
  }

  @Override
  public void transitionToOpenState() {
    cb.transitionToOpenState();
  }

  @Override
  public void transitionToOpenStateFor(Duration waitDuration) {
    cb.transitionToOpenStateFor(waitDuration);
  }

  @Override
  public void transitionToOpenStateUntil(Instant waitUntil) {
    cb.transitionToOpenStateUntil(waitUntil);
  }

  @Override
  public void transitionToHalfOpenState() {
    cb.transitionToHalfOpenState();
  }

  @Override
  public void transitionToDisabledState() {
    cb.transitionToDisabledState();
  }

  @Override
  public void transitionToMetricsOnlyState() {
    cb.transitionToMetricsOnlyState();
  }

  @Override
  public void transitionToForcedOpenState() {
    cb.transitionToForcedOpenState();
  }

  @Override
  public String getName() {
    return cb.getName();
  }

  @Override
  public State getState() {
    return cb.getState();
  }

  @Override
  public CircuitBreakerConfig getCircuitBreakerConfig() {
    return cb.getCircuitBreakerConfig();
  }

  @Override
  public Metrics getMetrics() {
    return cb.getMetrics();
  }

  @Override
  public Map<String, String> getTags() {
    return cb.getTags();
  }

  @Override
  public EventPublisher getEventPublisher() {
    return cb.getEventPublisher();
  }

  @Override
  public long getCurrentTimestamp() {
    System.err.println("getCurrentTimestamp");
    return cb.getCurrentTimestamp();
  }

  @Override
  public TimeUnit getTimestampUnit() {
    return cb.getTimestampUnit();
  }
}

package datadog.trace.instrumentation.resilience4j;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
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

  @Override
  public boolean tryAcquirePermission() {
    return cb.tryAcquirePermission();
  }

  @Override
  public void releasePermission() {
    cb.releasePermission();
  }

  @Override
  public void acquirePermission() {
    cb.acquirePermission(); // TODO do we want to record non-permitted attempt, then need to catch
    // exception, or better use emitted event
    // capture context after acquiring permission

    AgentSpan parent = AgentTracer.activeSpan();
    AgentSpanContext parentContext =
        parent != null ? parent.context() : AgentTracer.noopSpanContext();

    if (parent == null || !parent.getSpanName().equals("resilience4j")) {
      span = AgentTracer.startSpan("resilience4j", "resilience4j", parentContext);
      scope = AgentTracer.activateSpan(span);
    }
  }

  @Override
  public void onError(long duration, TimeUnit durationUnit, Throwable throwable) {
    if (scope != null) scope.close();
    if (span != null) span.finish();
    cb.onError(duration, durationUnit, throwable);
  }

  @Override
  public void onSuccess(long duration, TimeUnit durationUnit) {
    cb.onSuccess(duration, durationUnit);
  }

  @Override
  public void onResult(long duration, TimeUnit durationUnit, Object result) {
    if (scope != null) scope.close();
    if (span != null) span.finish();
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
    return cb.getCurrentTimestamp();
  }

  @Override
  public TimeUnit getTimestampUnit() {
    return cb.getTimestampUnit();
  }
}

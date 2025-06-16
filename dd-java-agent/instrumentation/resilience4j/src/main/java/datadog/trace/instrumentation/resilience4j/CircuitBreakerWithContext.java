package datadog.trace.instrumentation.resilience4j;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class CircuitBreakerWithContext implements CircuitBreaker {
  private final CircuitBreaker delegate;
  private final DDContext ddContext;

  public CircuitBreakerWithContext(CircuitBreaker delegate, DDContext ddContext) {
    this.delegate = delegate;
    this.ddContext = ddContext;
  }

  @Override
  public boolean tryAcquirePermission() {
    if (!delegate.tryAcquirePermission()) {
      // TODO do we want to record non-permitted attempt?
      return false;
    }

    ddContext.openScope();
    return true;
  }

  @Override
  public void acquirePermission() {
    delegate
        .acquirePermission(); // TODO do we want to record non-permitted attempt, then need to catch

    ddContext.openScope();
  }

  @Override
  public void releasePermission() {
    System.err.println("releasePermission");
    // TODO should close the scope and finish the span?

    delegate.releasePermission();
  }

  @Override
  public void onError(long duration, TimeUnit durationUnit, Throwable throwable) {
    ddContext.closeScope();
    ddContext.finishSpan(throwable);
    delegate.onError(duration, durationUnit, throwable);
  }

  @Override
  public void onSuccess(long duration, TimeUnit durationUnit) {
    ddContext.closeScope();
    ddContext.finishSpan(null);
    delegate.onSuccess(duration, durationUnit);
  }

  @Override
  public void onResult(long duration, TimeUnit durationUnit, Object result) {
    ddContext.closeScope();
    ddContext.finishSpan(null);
    delegate.onResult(duration, durationUnit, result);
  }

  @Override
  public void reset() {
    delegate.reset();
  }

  @Override
  public void transitionToClosedState() {
    delegate.transitionToClosedState();
  }

  @Override
  public void transitionToOpenState() {
    delegate.transitionToOpenState();
  }

  @Override
  public void transitionToOpenStateFor(Duration waitDuration) {
    delegate.transitionToOpenStateFor(waitDuration);
  }

  @Override
  public void transitionToOpenStateUntil(Instant waitUntil) {
    delegate.transitionToOpenStateUntil(waitUntil);
  }

  @Override
  public void transitionToHalfOpenState() {
    delegate.transitionToHalfOpenState();
  }

  @Override
  public void transitionToDisabledState() {
    delegate.transitionToDisabledState();
  }

  @Override
  public void transitionToMetricsOnlyState() {
    delegate.transitionToMetricsOnlyState();
  }

  @Override
  public void transitionToForcedOpenState() {
    delegate.transitionToForcedOpenState();
  }

  @Override
  public String getName() {
    return delegate.getName();
  }

  @Override
  public State getState() {
    return delegate.getState();
  }

  @Override
  public CircuitBreakerConfig getCircuitBreakerConfig() {
    return delegate.getCircuitBreakerConfig();
  }

  @Override
  public Metrics getMetrics() {
    return delegate.getMetrics();
  }

  @Override
  public Map<String, String> getTags() {
    return delegate.getTags();
  }

  @Override
  public EventPublisher getEventPublisher() {
    return delegate.getEventPublisher();
  }

  @Override
  public long getCurrentTimestamp() {
    System.err.println("getCurrentTimestamp");
    return delegate.getCurrentTimestamp();
  }

  @Override
  public TimeUnit getTimestampUnit() {
    return delegate.getTimestampUnit();
  }
}

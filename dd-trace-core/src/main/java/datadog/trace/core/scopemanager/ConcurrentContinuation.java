package datadog.trace.core.scopemanager;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

/**
 * This class must not be a nested class of ContinuableScope to avoid an unconstrained chain of
 * references (using too much memory).
 *
 * <p>This {@link AbstractContinuation} differs from the {@link SingleContinuation} in that if it is
 * activated, it needs to be canceled in addition to the returned {@link AgentScope} being closed.
 * This is to allow multiple concurrent threads that activate the continuation to race in a safe
 * way, and close the scopes without fear of closing the related {@link AgentSpan} prematurely.
 */
final class ConcurrentContinuation extends AbstractContinuation {
  private static final int START = 1;
  private static final int CLOSED = Integer.MIN_VALUE >> 1;
  private static final int BARRIER = Integer.MIN_VALUE >> 2;
  private volatile int count = START;

  private static final AtomicIntegerFieldUpdater<ConcurrentContinuation> COUNT =
      AtomicIntegerFieldUpdater.newUpdater(ConcurrentContinuation.class, "count");

  public ConcurrentContinuation(
      ContinuableScopeManager scopeManager, AgentSpan spanUnderScope, byte source) {
    super(scopeManager, spanUnderScope, source);
  }

  private boolean tryActivate() {
    int current = COUNT.incrementAndGet(this);
    if (current < START) {
      COUNT.decrementAndGet(this);
    }
    return current > START;
  }

  private boolean tryClose() {
    int current = COUNT.get(this);
    if (current < BARRIER) {
      return false;
    }
    // Now decrement the counter
    current = COUNT.decrementAndGet(this);
    // Try to close this if we are between START and BARRIER
    while (current < START && current > BARRIER) {
      if (COUNT.compareAndSet(this, current, CLOSED)) {
        return true;
      }
      current = COUNT.get(this);
    }
    return false;
  }

  @Override
  public AgentScope activate() {
    if (tryActivate()) {
      return scopeManager.continueSpan(this, spanUnderScope, source);
    } else {
      return null;
    }
  }

  @Override
  public void cancel() {
    if (tryClose()) {
      traceCollector.cancelContinuation(this);
    }
    ContinuableScopeManager.log.debug(
        "t_id={} -> canceling continuation {}", spanUnderScope.getTraceId(), this);
  }

  @Override
  public AgentSpan getSpan() {
    return spanUnderScope;
  }

  @Override
  void cancelFromContinuedScopeClose() {
    cancel();
  }

  @Override
  public String toString() {
    int c = COUNT.get(this);
    String s = c < BARRIER ? "CANCELED" : String.valueOf(c);
    return getClass().getSimpleName()
        + "@"
        + Integer.toHexString(hashCode())
        + "("
        + s
        + ")->"
        + spanUnderScope;
  }
}

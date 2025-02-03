package datadog.trace.core.scopemanager;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTraceCollector;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

/**
 * Used to pass async context between workers. A trace will not be reported until all spans and
 * continuations are resolved. You must call activate (and close on the returned scope) or cancel on
 * each continuation to avoid discarding traces.
 *
 * <p>This class must not be a nested class of ContinuableScope to avoid an unconstrained chain of
 * references (using too much memory).
 */
final class ScopeContinuation implements AgentScope.Continuation {
  private static final AtomicIntegerFieldUpdater<ScopeContinuation> COUNT =
      AtomicIntegerFieldUpdater.newUpdater(ScopeContinuation.class, "count");

  // these boundaries were selected to allow for speculative counting and fuzzy checks
  private static final int CANCELLED = Integer.MIN_VALUE >> 1;
  private static final int BARRIER = Integer.MIN_VALUE >> 2;
  private static final int HELD = (Integer.MAX_VALUE >> 1) + 1;

  final ContinuableScopeManager scopeManager;
  final AgentSpan spanUnderScope;
  final byte source;
  final AgentTraceCollector traceCollector;

  private volatile int count = 0;

  ScopeContinuation(
      final ContinuableScopeManager scopeManager,
      final AgentSpan spanUnderScope,
      final byte source) {
    this.scopeManager = scopeManager;
    this.spanUnderScope = spanUnderScope;
    this.source = source;

    this.traceCollector = spanUnderScope.context().getTraceCollector();
  }

  ScopeContinuation register() {
    traceCollector.registerContinuation(this);
    return this;
  }

  @Override
  public AgentScope.Continuation hold() {
    // update initial count to record that this continuation has a hold
    COUNT.compareAndSet(this, 0, HELD);
    return this;
  }

  @Override
  public AgentScope activate() {
    if (COUNT.incrementAndGet(this) > 0) {
      // speculative update succeeded, continuation can be activated
      return scopeManager.continueSpan(this, spanUnderScope, source);
    } else {
      // continuation cancelled or too many activations; rollback count
      COUNT.decrementAndGet(this);
      return null;
    }
  }

  @Override
  public void cancel() {
    int current = count;
    while (current >= HELD) {
      // remove the hold on this continuation by removing the offset
      COUNT.compareAndSet(this, current, current - HELD);
      current = count;
    }
    while (current <= 0 && current > BARRIER) {
      // no outstanding activations and hold has been removed
      if (COUNT.compareAndSet(this, current, CANCELLED)) {
        traceCollector.cancelContinuation(this);
        return;
      }
      current = count;
    }
  }

  void cancelFromContinuedScopeClose() {
    if (COUNT.compareAndSet(this, 1, CANCELLED)) {
      // fast path: only one activation of the continuation (no hold)
      traceCollector.cancelContinuation(this);
    } else if (COUNT.decrementAndGet(this) == 0) {
      // slow path: multiple activations, all have now closed (no hold)
      cancel();
    } /* else there are outstanding activations or hold is in place */
  }

  @Override
  public AgentSpan getSpan() {
    return spanUnderScope;
  }

  @Override
  public String toString() {
    return getClass().getSimpleName()
        + "@"
        + Integer.toHexString(hashCode())
        + "->"
        + spanUnderScope;
  }
}

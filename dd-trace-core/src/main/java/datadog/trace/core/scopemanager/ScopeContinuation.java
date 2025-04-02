package datadog.trace.core.scopemanager;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopScope;

import datadog.context.Context;
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
  private static final int HELD = (Integer.MAX_VALUE >> 1) + 1;

  final ContinuableScopeManager scopeManager;
  final Context context;
  final byte source;
  final AgentTraceCollector traceCollector;

  /**
   * When positive this reflects the number of outstanding activations as well as whether there is
   * an active hold on the continuation:
   *
   * <table>
   * <tr><th>Value</th> <th>Meaning</th></tr>
   * <tr><td>0</td><td>Not held or activated</td></tr>
   * <tr><td>1..HELD-1</td><td>Activated, not held</td></tr>
   * <tr><td>HELD</td><td>Held, not yet activated</td></tr>
   * <tr><td>HELD..MAX_INT</td><td>Activated and held</td></tr>
   * </table>
   *
   * where HELD is at the mid-point between 1 and MAX_INT.
   *
   * <p>A negative value of CANCELLED reflects that the continuation has either been activated and
   * all associated scopes are now closed, or it has been explicitly cancelled. This value was
   * chosen to be half the size of MIN_INT to avoid speculative additions in {@link #activate()}
   * from overflowing to a positive count.
   */
  private volatile int count = 0;

  ScopeContinuation(
      final ContinuableScopeManager scopeManager,
      final Context context,
      final byte source,
      final AgentTraceCollector traceCollector) {
    this.scopeManager = scopeManager;
    this.context = context;
    this.source = source;
    this.traceCollector = traceCollector;
  }

  ScopeContinuation register() {
    traceCollector.registerContinuation(this);
    scopeManager.healthMetrics.onCaptureContinuation();
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
      return scopeManager.continueSpan(this, context, source);
    } else {
      // continuation cancelled or too many activations; rollback count
      COUNT.decrementAndGet(this);
      return noopScope();
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
    while (current == 0) {
      // no outstanding activations and hold has been removed
      if (COUNT.compareAndSet(this, current, CANCELLED)) {
        traceCollector.removeContinuation(this);
        scopeManager.healthMetrics.onFinishContinuation();
        return;
      }
      current = count;
    }
    scopeManager.healthMetrics.onCancelContinuation();
  }

  void cancelFromContinuedScopeClose() {
    if (COUNT.compareAndSet(this, 1, CANCELLED)) {
      // fast path: only one activation of the continuation (no hold)
      traceCollector.removeContinuation(this);
      scopeManager.healthMetrics.onFinishContinuation();
    } else if (COUNT.decrementAndGet(this) == 0) {
      // slow path: multiple activations, all have now closed (no hold)
      cancel();
    } /* else there are outstanding activations or hold is in place */
  }

  @Override
  public AgentSpan span() {
    return AgentSpan.fromContext(context);
  }

  @Override
  public String toString() {
    return getClass().getSimpleName() + "@" + Integer.toHexString(hashCode()) + "->" + context;
  }
}

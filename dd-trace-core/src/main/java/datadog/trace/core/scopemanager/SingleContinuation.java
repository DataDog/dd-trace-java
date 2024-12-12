package datadog.trace.core.scopemanager;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

/**
 * This class must not be a nested class of ContinuableScope to avoid an unconstrained chain of
 * references (using too much memory).
 */
final class SingleContinuation extends AbstractContinuation {
  private static final AtomicIntegerFieldUpdater<SingleContinuation> USED =
      AtomicIntegerFieldUpdater.newUpdater(SingleContinuation.class, "used");
  private volatile int used = 0;

  SingleContinuation(
      final ContinuableScopeManager scopeManager,
      final AgentSpan spanUnderScope,
      final byte source) {
    super(scopeManager, spanUnderScope, source);
  }

  @Override
  public AgentScope activate() {
    if (USED.compareAndSet(this, 0, 1)) {
      return scopeManager.continueSpan(this, spanUnderScope, source);
    } else {
      ContinuableScopeManager.log.debug(
          "Failed to activate continuation. Reusing a continuation not allowed. Spans may be reported separately.");
      return scopeManager.continueSpan(null, spanUnderScope, source);
    }
  }

  @Override
  public void cancel() {
    if (USED.compareAndSet(this, 0, 1)) {
      traceCollector.cancelContinuation(this);
    } else {
      ContinuableScopeManager.log.debug("Failed to close continuation {}. Already used.", this);
    }
  }

  @Override
  public AgentSpan getSpan() {
    return spanUnderScope;
  }

  @Override
  void cancelFromContinuedScopeClose() {
    traceCollector.cancelContinuation(this);
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

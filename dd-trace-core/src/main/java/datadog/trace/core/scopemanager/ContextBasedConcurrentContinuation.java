package datadog.trace.core.scopemanager;

import static datadog.trace.core.scopemanager.ContextBasedScopeManager.LOGGER;

import datadog.trace.bootstrap.instrumentation.api.AgentScopeManager;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.ScopeSource;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

public class ContextBasedConcurrentContinuation extends ContextBasedAbstractContinuation {
  private static final int START = 1;
  private static final int CLOSED = Integer.MIN_VALUE >> 1;
  private static final int BARRIER = Integer.MIN_VALUE >> 2;
  private volatile int count;

  private static final AtomicIntegerFieldUpdater<ContextBasedConcurrentContinuation> COUNT =
      AtomicIntegerFieldUpdater.newUpdater(ContextBasedConcurrentContinuation.class, "count");

  ContextBasedConcurrentContinuation(
      AgentScopeManager scopeManager, AgentSpan span, ScopeSource source) {
    super(scopeManager, span, source);
    this.count = START;
  }

  @Override
  protected boolean tryActivate() {
    int current = COUNT.incrementAndGet(this);
    if (current < START) {
      COUNT.decrementAndGet(this);
    }
    return current > START;
  }

  @Override
  protected boolean tryCancel() {
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
  public void cancel() {
    super.cancel();
    LOGGER.debug("t_id={} -> canceling continuation {}", getSpan().getTraceId(), this);
  }
}

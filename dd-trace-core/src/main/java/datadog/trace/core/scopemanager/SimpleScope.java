package datadog.trace.core.scopemanager;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.context.ScopeListener;
import java.util.concurrent.atomic.AtomicInteger;

/** Simple scope implementation which does not propagate across threads. */
public class SimpleScope implements DDScope {
  private final ContextualScopeManager scopeManager;
  private final AgentSpan spanUnderScope;
  private final boolean finishOnClose;
  private final DDScope toRestore;
  private final int depth;
  private final AtomicInteger referenceCount = new AtomicInteger(1);

  public SimpleScope(
      final ContextualScopeManager scopeManager,
      final AgentSpan spanUnderScope,
      final boolean finishOnClose) {
    assert spanUnderScope != null : "span must not be null";
    this.scopeManager = scopeManager;
    this.spanUnderScope = spanUnderScope;
    this.finishOnClose = finishOnClose;
    toRestore = ContextualScopeManager.tlsScope.get();
    ContextualScopeManager.tlsScope.set(this);
    depth = toRestore == null ? 0 : toRestore.depth() + 1;
    for (final ScopeListener listener : scopeManager.scopeListeners) {
      listener.afterScopeActivated();
    }
  }

  @Override
  public void close() {
    if (referenceCount.decrementAndGet() > 0) {
      return;
    }
    if (finishOnClose) {
      spanUnderScope.finish();
    }
    for (final ScopeListener listener : scopeManager.scopeListeners) {
      listener.afterScopeClosed();
    }

    if (ContextualScopeManager.tlsScope.get() == this) {
      ContextualScopeManager.tlsScope.set(toRestore);
      if (toRestore != null) {
        for (final ScopeListener listener : scopeManager.scopeListeners) {
          listener.afterScopeActivated();
        }
      }
    }
  }

  @Override
  public AgentSpan span() {
    return spanUnderScope;
  }

  @Override
  public void setAsyncPropagation(final boolean value) {
    // do nothing
  }

  @Override
  public int depth() {
    return depth;
  }

  @Override
  public DDScope incrementReferences() {
    referenceCount.incrementAndGet();
    return this;
  }
}

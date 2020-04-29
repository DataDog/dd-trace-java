package datadog.opentracing.scopemanager;

import datadog.trace.context.ScopeListener;
import io.opentracing.Span;
import java.util.concurrent.atomic.AtomicInteger;

/** Simple scope implementation which does not propagate across threads. */
public class SimpleScope implements DDScope {
  private final ContextualScopeManager scopeManager;
  private final Span spanUnderScope;
  private final boolean finishOnClose;
  private final DDScope toRestore;
  private final int depth;
  private final AtomicInteger referenceCount = new AtomicInteger(1);

  public SimpleScope(
      final ContextualScopeManager scopeManager,
      final Span spanUnderScope,
      final boolean finishOnClose) {
    assert spanUnderScope != null : "span must not be null";
    this.scopeManager = scopeManager;
    this.spanUnderScope = spanUnderScope;
    this.finishOnClose = finishOnClose;
    toRestore = scopeManager.tlsScope.get();
    scopeManager.tlsScope.set(this);
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

    if (scopeManager.tlsScope.get() == this) {
      scopeManager.tlsScope.set(toRestore);
      if (toRestore != null) {
        for (final ScopeListener listener : scopeManager.scopeListeners) {
          listener.afterScopeActivated();
        }
      }
    }
  }

  @Override
  public Span span() {
    return spanUnderScope;
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

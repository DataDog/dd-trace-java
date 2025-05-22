package datadog.trace.core.scopemanager;

import datadog.context.Context;
import datadog.trace.api.Stateful;

final class ContinuingScope extends ContinuableScope {
  /** Continuation that created this scope. */
  private final ScopeContinuation continuation;

  ContinuingScope(
      final ContinuableScopeManager scopeManager,
      final Context context,
      final byte source,
      final boolean isAsyncPropagating,
      final ScopeContinuation continuation,
      final Stateful scopeState) {
    super(scopeManager, context, source, isAsyncPropagating, scopeState);
    this.continuation = continuation;
  }

  @Override
  void cleanup(final ScopeStack scopeStack) {
    super.cleanup(scopeStack);
    continuation.cancelFromContinuedScopeClose();
  }
}

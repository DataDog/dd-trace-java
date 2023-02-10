package datadog.trace.core.scopemanager;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

final class ContinuingScope extends ContinuableScope {
  /** Continuation that created this scope. */
  private final AbstractContinuation continuation;

  ContinuingScope(
      final ContinuableScopeManager scopeManager,
      final AgentSpan span,
      final byte source,
      final boolean isAsyncPropagating,
      final AbstractContinuation continuation) {
    super(scopeManager, span, source, isAsyncPropagating);
    this.continuation = continuation;
  }

  @Override
  void cleanup(final ScopeStack scopeStack) {
    super.cleanup(scopeStack);

    continuation.cancelFromContinuedScopeClose();
  }
}

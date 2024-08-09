package datadog.trace.core.scopemanager;

import datadog.context.ContextScope;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.ScopeSource;

public class ContextBasedScope implements AgentScope {
  private final ContextBasedScopeManager scopeManager;
  private final AgentSpan span;
  private final ScopeSource source;
  private ContextScope contextScope;
  /** Flag to propagate this scope across async boundaries. */
  private boolean asyncPropagating;

  public ContextBasedScope(
      ContextBasedScopeManager scopeManager,
      AgentSpan span,
      ScopeSource source,
      boolean isAsyncPropagating) {
    this.scopeManager = scopeManager;
    this.span = span;
    this.source = source;
    this.asyncPropagating = isAsyncPropagating;
  }

  void attachContextScope(ContextScope contextScope) {
    this.contextScope = contextScope;
  }

  @Override
  public AgentSpan span() {
    return this.span;
  }

  @Override
  public byte source() {
    return this.source.id();
  }

  @Override
  public Continuation capture() {
    return this.asyncPropagating
        ? new ContextBasedContinuation(this.scopeManager, this.span, this.source)
        : null;
  }

  @Override
  public Continuation captureConcurrent() {
    return this.asyncPropagating
        ? new ContextBasedContinuation(this.scopeManager, this.span, this.source)
        : null;
  }

  @Override
  public boolean isAsyncPropagating() {
    return asyncPropagating;
  }

  @Override
  public void setAsyncPropagation(boolean asyncPropagation) {
    this.asyncPropagating = asyncPropagation;
  }

  @Override
  public void close() {
    this.scopeManager.healthMetrics.onCloseScope();
    this.contextScope.close();
  }
}

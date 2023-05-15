package datadog.trace.core.scopemanager;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentScopeContext;
import datadog.trace.bootstrap.instrumentation.api.AgentTrace;

/**
 * This class must not be a nested class of ContinuableScope to avoid an unconstrained chain of
 * references (using too much memory).
 */
abstract class AbstractContinuation implements AgentScope.Continuation {

  final ContinuableScopeManager scopeManager;
  final AgentScopeContext context;
  final byte source;
  final AgentTrace trace;

  public AbstractContinuation(
      ContinuableScopeManager scopeManager, AgentScopeContext context, byte source) {
    this.scopeManager = scopeManager;
    this.context = context;
    this.source = source;
    this.trace = context.span().context().getTrace();
  }

  AbstractContinuation register() {
    trace.registerContinuation(this);
    return this;
  }

  // Called by ContinuableScopeManager when a continued scope is closed
  // Can't use cancel() for SingleContinuation because of the "used" check
  abstract void cancelFromContinuedScopeClose();
}

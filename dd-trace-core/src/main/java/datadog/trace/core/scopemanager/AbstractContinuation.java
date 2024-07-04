package datadog.trace.core.scopemanager;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTraceCollector;

/**
 * This class must not be a nested class of ContinuableScope to avoid an unconstrained chain of
 * references (using too much memory).
 */
abstract class AbstractContinuation implements AgentScope.Continuation {

  final ContinuableScopeManager scopeManager;
  final AgentSpan spanUnderScope;
  final byte source;
  final AgentTraceCollector traceCollector;

  public AbstractContinuation(
      ContinuableScopeManager scopeManager, AgentSpan spanUnderScope, byte source) {
    this.scopeManager = scopeManager;
    this.spanUnderScope = spanUnderScope;
    this.source = source;
    this.traceCollector = spanUnderScope.context().getTraceCollector();
  }

  AbstractContinuation register() {
    traceCollector.registerContinuation(this);
    return this;
  }

  // Called by ContinuableScopeManager when a continued scope is closed
  // Can't use cancel() for SingleContinuation because of the "used" check
  abstract void cancelFromContinuedScopeClose();
}

package datadog.trace.bootstrap.instrumentation.api;

import datadog.trace.api.Baggage;

/**
 * Allows custom scope managers. See OTScopeManager, CustomScopeManager, and ContextualScopeManager
 */
public interface AgentScopeManager extends ScopeStateAware {

  AgentScope activate(AgentSpan span, ScopeSource source);

  AgentScope activate(AgentSpan span, ScopeSource source, boolean isAsyncPropagating);

  AgentScope active();

  AgentSpan activeSpan();

  AgentScope.Continuation captureSpan(AgentSpan span);

  void closePrevious(boolean finishSpan);

  AgentScope activateNext(AgentSpan span);

  AgentScope activateBaggage(Baggage baggage);
}

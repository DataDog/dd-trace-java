package datadog.trace.bootstrap.instrumentation.api;

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
}

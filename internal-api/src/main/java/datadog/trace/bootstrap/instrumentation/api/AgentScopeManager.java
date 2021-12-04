package datadog.trace.bootstrap.instrumentation.api;

/**
 * Allows custom scope managers. See OTScopeManager, CustomScopeManager, and ContextualScopeManager
 */
public interface AgentScopeManager {

  AgentScope activate(AgentSpan span, ScopeSource source);

  AgentScope activate(AgentSpan span, ScopeSource source, boolean isAsyncPropagating);

  AgentScope active();

  AgentSpan activeSpan();

  AgentScope.Continuation captureSpan(AgentSpan span, ScopeSource source);

  void closePrevious(boolean finishSpan);

  AgentScope activateNext(AgentSpan span);
}

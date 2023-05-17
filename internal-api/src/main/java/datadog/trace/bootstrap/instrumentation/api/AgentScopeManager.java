package datadog.trace.bootstrap.instrumentation.api;

/**
 * Allows custom scope managers. See OTScopeManager, CustomScopeManager, and ContextualScopeManager
 */
public interface AgentScopeManager extends ScopeStateAware {

  AgentScope activate(AgentScopeContext context, ScopeSource source);

  AgentScope activate(AgentScopeContext context, ScopeSource source, boolean isAsyncPropagating);

  AgentScope active();

  AgentSpan activeSpan();

  AgentScopeContext activeContext();

  AgentScope.Continuation capture(AgentScopeContext context);

  void closePrevious(boolean finishSpan);

  AgentScope activateNext(AgentSpan span);

  AgentScope activateContext(AgentScopeContext context);
}

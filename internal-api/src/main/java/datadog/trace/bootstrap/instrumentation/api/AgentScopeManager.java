package datadog.trace.bootstrap.instrumentation.api;

import datadog.trace.context.TraceScope;

/**
 * Allows custom scope managers. See OTScopeManager, CustomScopeManager, and ContextualScopeManager
 */
public interface AgentScopeManager {
  AgentScope activate(AgentSpan span);

  TraceScope active();

  AgentSpan activeSpan();
}

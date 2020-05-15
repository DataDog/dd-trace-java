package datadog.trace.core.scopemanager;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

interface ScopeInterceptor {
  AgentScope handleSpan(AgentSpan span);
}

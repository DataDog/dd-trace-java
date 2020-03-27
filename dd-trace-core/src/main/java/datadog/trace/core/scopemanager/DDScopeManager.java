package datadog.trace.core.scopemanager;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

public interface DDScopeManager {
  AgentScope activate(AgentSpan span, boolean finishOnClose);

  AgentScope active();

  AgentSpan activeSpan();
}

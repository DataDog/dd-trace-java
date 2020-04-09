package datadog.trace.core.scopemanager;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.context.TraceScope;

// Intentionally package private.
interface DDScope extends AgentScope, TraceScope {
  int depth();

  DDScope incrementReferences();
}

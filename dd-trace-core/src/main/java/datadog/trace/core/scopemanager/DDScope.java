package datadog.trace.core.scopemanager;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;

// Intentionally package private.
interface DDScope extends AgentScope {
  int depth();

  DDScope incrementReferences();
}

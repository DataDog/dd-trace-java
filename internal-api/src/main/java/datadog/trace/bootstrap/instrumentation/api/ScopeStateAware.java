package datadog.trace.bootstrap.instrumentation.api;

public interface ScopeStateAware {
  ScopeState newScopeState();
}

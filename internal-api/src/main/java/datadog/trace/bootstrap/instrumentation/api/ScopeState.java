package datadog.trace.bootstrap.instrumentation.api;

public interface ScopeState {
  void activate();

  ScopeState copy();
}

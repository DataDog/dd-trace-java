package datadog.trace.bootstrap.instrumentation.api;

public interface ScopeState {
  void activate();

  void fetchFromActive();
}

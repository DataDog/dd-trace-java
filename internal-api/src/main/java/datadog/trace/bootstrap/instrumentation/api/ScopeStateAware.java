package datadog.trace.bootstrap.instrumentation.api;

public interface ScopeStateAware {
  /** @return The old connected scope stack */
  ScopeState oldScopeState();

  /** @return A new disconnected scope stack */
  ScopeState newScopeState();
}

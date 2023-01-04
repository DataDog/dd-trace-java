package datadog.trace.bootstrap.instrumentation.api;

public interface ScopeStateAware {
  default ScopeState newScopeState() {
    return ScopeState.NO_OP;
  }
}

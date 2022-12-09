package datadog.trace.bootstrap.instrumentation.api;

public interface ManagedScopeAware {
  ManagedScope delegateManagedScope();
}

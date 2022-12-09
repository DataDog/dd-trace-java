package datadog.trace.bootstrap.instrumentation.api;

public interface ManagedScope {
  void activate();

  void fetch();
}

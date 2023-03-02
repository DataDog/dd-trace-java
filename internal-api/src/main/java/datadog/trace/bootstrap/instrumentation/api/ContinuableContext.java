package datadog.trace.bootstrap.instrumentation.api;

public interface ContinuableContext {
  void activate();

  void deactivate();
}

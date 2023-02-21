package datadog.trace.bootstrap.instrumentation.api;

public interface ProfilingContext {
  void apply();

  void clear();
}

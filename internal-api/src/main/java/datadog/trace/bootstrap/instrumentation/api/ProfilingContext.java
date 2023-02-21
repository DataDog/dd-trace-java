package datadog.trace.bootstrap.instrumentation.api;

public interface ProfilingContext {
  void apply();

  void set(int offset, int value);

  void clear();
}

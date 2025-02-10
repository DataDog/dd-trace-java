package datadog.trace.api.profiling;

public interface ProfilingSnapshot {
  enum Kind {
    PERIODIC,
    ON_SHUTDOWN
  }
}

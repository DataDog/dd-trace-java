package datadog.trace.api.profiling;

public interface ProfilingSnapshot extends ObservableType {
  enum SnapshotKind {
    PERIODIC,
    ON_SHUTDOWN
  }
}

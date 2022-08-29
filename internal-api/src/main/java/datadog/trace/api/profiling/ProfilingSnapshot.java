package datadog.trace.api.profiling;

public interface ProfilingSnapshot extends ObservableType {
  enum SnapshotReason {
    REGULAR,
    ON_SHUTDOWN
  }
}

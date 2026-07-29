package datadog.openfeature.internal.core;

/** Lifecycle status for a configuration source. */
public enum SourceStatus {
  NEW,
  STARTING,
  READY,
  ERROR,
  CLOSED
}

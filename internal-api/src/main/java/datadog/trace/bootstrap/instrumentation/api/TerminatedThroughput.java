package datadog.trace.bootstrap.instrumentation.api;

public class TerminatedThroughput implements InboxItem {
  public long getTimestampNanos() {
    return timestampNanos;
  }
  private final long timestampNanos;

  public TerminatedThroughput(long timestampNanos) {
    this.timestampNanos = timestampNanos;
  }
}

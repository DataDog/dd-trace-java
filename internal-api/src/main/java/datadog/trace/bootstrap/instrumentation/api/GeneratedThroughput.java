package datadog.trace.bootstrap.instrumentation.api;

public class GeneratedThroughput implements InboxItem {
  public long getTimestampNanos() {
    return timestampNanos;
  }
  private final long timestampNanos;

  public GeneratedThroughput(long timestampNanos) {
    this.timestampNanos = timestampNanos;
  }
}

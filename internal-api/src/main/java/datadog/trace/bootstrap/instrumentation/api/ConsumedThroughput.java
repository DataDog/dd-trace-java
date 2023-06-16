package datadog.trace.bootstrap.instrumentation.api;

public class ConsumedThroughput implements InboxItem {
  public long getTimestampNanos() {
    return timestampNanos;
  }
  private final long timestampNanos;

  public ConsumedThroughput(long timestampNanos) {
    this.timestampNanos = timestampNanos;
  }
}

package datadog.trace.bootstrap.instrumentation.api;

public class ProducedThroughput implements InboxItem {
  public long getTimestampNanos() {
    return timestampNanos;
  }
  private final long timestampNanos;

  public ProducedThroughput(long timestampNanos) {
    this.timestampNanos = timestampNanos;
  }
}

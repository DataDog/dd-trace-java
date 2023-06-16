package datadog.trace.bootstrap.instrumentation.api;

public class FanOutThroughput implements InboxItem {
  public long getTimestampNanos() {
    return timestampNanos;
  }
  private final long timestampNanos;

  public FanOutThroughput(long timestampNanos) {
    this.timestampNanos = timestampNanos;
  }
}

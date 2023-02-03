package datadog.trace.bootstrap.instrumentation.api;

import java.util.List;

public class Backlog implements InboxItem {
  public List<String> getSortedTags() {
    return sortedTags;
  }

  public long getValue() {
    return value;
  }

  public long getTimestampNanos() {
    return timestampNanos;
  }

  private final List<String> sortedTags;
  private final long value;
  private final long timestampNanos;

  public Backlog(List<String> sortedTags, long value, long timestampNanos) {
    this.sortedTags = sortedTags;
    this.value = value;
    this.timestampNanos = timestampNanos;
  }
}

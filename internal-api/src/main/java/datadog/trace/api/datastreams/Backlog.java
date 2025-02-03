package datadog.trace.api.datastreams;

import java.util.List;

// Backlog allows us to track the size of a queue in data streams. For example, by monitoring both
// the consumer and the producer,
// we can get the size in bytes of a Kafka queue.
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

  public String getServiceNameOverride() {
    return serviceNameOverride;
  }

  private final List<String> sortedTags;
  private final long value;
  private final long timestampNanos;
  private final String serviceNameOverride;

  public Backlog(
      List<String> sortedTags, long value, long timestampNanos, String serviceNameOverride) {
    this.sortedTags = sortedTags;
    this.value = value;
    this.timestampNanos = timestampNanos;
    this.serviceNameOverride = serviceNameOverride;
  }
}

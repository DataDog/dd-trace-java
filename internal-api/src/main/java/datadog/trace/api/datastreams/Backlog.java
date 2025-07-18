package datadog.trace.api.datastreams;

// Backlog allows us to track the size of a queue in data streams. For example, by monitoring both
// the consumer and the producer,
// we can get the size in bytes of a Kafka queue.
public class Backlog implements InboxItem {
  public DataStreamsTags getTags() {
    return tags;
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

  private final DataStreamsTags tags;
  private final long value;
  private final long timestampNanos;
  private final String serviceNameOverride;

  public Backlog(
      DataStreamsTags tags, long value, long timestampNanos, String serviceNameOverride) {
    this.tags = tags;
    this.value = value;
    this.timestampNanos = timestampNanos;
    this.serviceNameOverride = serviceNameOverride;
  }
}

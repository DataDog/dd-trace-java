package datadog.trace.api.datastreams;

/**
 * SchemaRegistryUsage tracks usage of Confluent Schema Registry for data streams. This allows
 * monitoring schema compatibility checks, registrations, and failures.
 */
public class SchemaRegistryUsage implements InboxItem {
  private final String topic;
  private final String clusterId;
  private final int schemaId;
  private final boolean isSuccess;
  private final boolean isKey;
  private final long timestampNanos;
  private final String serviceNameOverride;

  public SchemaRegistryUsage(
      String topic,
      String clusterId,
      int schemaId,
      boolean isSuccess,
      boolean isKey,
      long timestampNanos,
      String serviceNameOverride) {
    this.topic = topic;
    this.clusterId = clusterId;
    this.schemaId = schemaId;
    this.isSuccess = isSuccess;
    this.isKey = isKey;
    this.timestampNanos = timestampNanos;
    this.serviceNameOverride = serviceNameOverride;
  }

  public String getTopic() {
    return topic;
  }

  public String getClusterId() {
    return clusterId;
  }

  public int getSchemaId() {
    return schemaId;
  }

  public boolean isSuccess() {
    return isSuccess;
  }

  public boolean isKey() {
    return isKey;
  }

  public long getTimestampNanos() {
    return timestampNanos;
  }

  public String getServiceNameOverride() {
    return serviceNameOverride;
  }
}

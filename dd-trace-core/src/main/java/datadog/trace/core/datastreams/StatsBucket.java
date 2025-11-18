package datadog.trace.core.datastreams;

import datadog.trace.api.datastreams.Backlog;
import datadog.trace.api.datastreams.DataStreamsTags;
import datadog.trace.api.datastreams.SchemaRegistryUsage;
import datadog.trace.api.datastreams.StatsPoint;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

public class StatsBucket {
  private final long startTimeNanos;
  private final long bucketDurationNanos;
  private final Map<Long, StatsGroup> hashToGroup = new HashMap<>();
  private final Map<DataStreamsTags, Long> backlogs = new HashMap<>();
  private final Map<SchemaKey, Long> schemaRegistryUsages = new HashMap<>();

  public StatsBucket(long startTimeNanos, long bucketDurationNanos) {
    this.startTimeNanos = startTimeNanos;
    this.bucketDurationNanos = bucketDurationNanos;
  }

  public void addPoint(StatsPoint statsPoint) {
    // we want to perform aggregation per dataset, to allow
    // lower-level granularity and unblock dataset name manipulations on the backend
    // without affecting the precision.
    hashToGroup
        .computeIfAbsent(
            statsPoint.getAggregationHash(),
            hash ->
                new StatsGroup(
                    statsPoint.getTags(), statsPoint.getHash(), statsPoint.getParentHash()))
        .add(
            statsPoint.getPathwayLatencyNano(),
            statsPoint.getEdgeLatencyNano(),
            statsPoint.getPayloadSizeBytes());
  }

  public void addBacklog(Backlog backlog) {
    backlogs.compute(
        backlog.getTags(),
        (k, v) -> (v == null) ? backlog.getValue() : Math.max(v, backlog.getValue()));
  }

  public void addSchemaRegistryUsage(SchemaRegistryUsage usage) {
    SchemaKey key =
        new SchemaKey(
            usage.getTopic(),
            usage.getClusterId(),
            usage.getSchemaId(),
            usage.isSuccess(),
            usage.isKey(),
            usage.getOperation());
    schemaRegistryUsages.merge(key, 1L, Long::sum);
  }

  public long getStartTimeNanos() {
    return startTimeNanos;
  }

  public long getBucketDurationNanos() {
    return bucketDurationNanos;
  }

  public Collection<StatsGroup> getGroups() {
    return hashToGroup.values();
  }

  public Collection<Map.Entry<DataStreamsTags, Long>> getBacklogs() {
    return backlogs.entrySet();
  }

  public Collection<Map.Entry<SchemaKey, Long>> getSchemaRegistryUsages() {
    return schemaRegistryUsages.entrySet();
  }

  /**
   * Key for aggregating schema registry usage by topic, cluster, schema ID, success, key/value
   * type, and operation.
   */
  public static class SchemaKey {
    private final String topic;
    private final String clusterId;
    private final int schemaId;
    private final boolean isSuccess;
    private final boolean isKey;
    private final String operation;

    public SchemaKey(
        String topic,
        String clusterId,
        int schemaId,
        boolean isSuccess,
        boolean isKey,
        String operation) {
      this.topic = topic;
      this.clusterId = clusterId;
      this.schemaId = schemaId;
      this.isSuccess = isSuccess;
      this.isKey = isKey;
      this.operation = operation;
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

    public String getOperation() {
      return operation;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      SchemaKey that = (SchemaKey) o;
      return schemaId == that.schemaId
          && isSuccess == that.isSuccess
          && isKey == that.isKey
          && java.util.Objects.equals(topic, that.topic)
          && java.util.Objects.equals(clusterId, that.clusterId)
          && java.util.Objects.equals(operation, that.operation);
    }

    @Override
    public int hashCode() {
      int result = topic != null ? topic.hashCode() : 0;
      result = 31 * result + (clusterId != null ? clusterId.hashCode() : 0);
      result = 31 * result + schemaId;
      result = 31 * result + (isSuccess ? 1 : 0);
      result = 31 * result + (isKey ? 1 : 0);
      result = 31 * result + (operation != null ? operation.hashCode() : 0);
      return result;
    }
  }
}

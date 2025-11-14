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
  private final Map<SchemaRegistryKey, SchemaRegistryCount> schemaRegistryUsages = new HashMap<>();

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
    SchemaRegistryKey key =
        new SchemaRegistryKey(
            usage.getTopic(),
            usage.getClusterId(),
            usage.getSchemaId(),
            usage.isSuccess(),
            usage.isKey());
    schemaRegistryUsages.compute(
        key, (k, v) -> (v == null) ? new SchemaRegistryCount(1) : v.increment());
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

  public Collection<Map.Entry<SchemaRegistryKey, SchemaRegistryCount>> getSchemaRegistryUsages() {
    return schemaRegistryUsages.entrySet();
  }

  /**
   * Key for aggregating schema registry usage by topic, cluster, schema ID, success, and key/value
   * type.
   */
  public static class SchemaRegistryKey {
    private final String topic;
    private final String clusterId;
    private final int schemaId;
    private final boolean isSuccess;
    private final boolean isKey;

    public SchemaRegistryKey(
        String topic, String clusterId, int schemaId, boolean isSuccess, boolean isKey) {
      this.topic = topic;
      this.clusterId = clusterId;
      this.schemaId = schemaId;
      this.isSuccess = isSuccess;
      this.isKey = isKey;
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

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      SchemaRegistryKey that = (SchemaRegistryKey) o;
      return schemaId == that.schemaId
          && isSuccess == that.isSuccess
          && isKey == that.isKey
          && java.util.Objects.equals(topic, that.topic)
          && java.util.Objects.equals(clusterId, that.clusterId);
    }

    @Override
    public int hashCode() {
      int result = topic != null ? topic.hashCode() : 0;
      result = 31 * result + (clusterId != null ? clusterId.hashCode() : 0);
      result = 31 * result + schemaId;
      result = 31 * result + (isSuccess ? 1 : 0);
      result = 31 * result + (isKey ? 1 : 0);
      return result;
    }
  }

  /** Count of schema registry usages. */
  public static class SchemaRegistryCount {
    private long count;

    public SchemaRegistryCount(long count) {
      this.count = count;
    }

    public SchemaRegistryCount increment() {
      count++;
      return this;
    }

    public long getCount() {
      return count;
    }
  }
}

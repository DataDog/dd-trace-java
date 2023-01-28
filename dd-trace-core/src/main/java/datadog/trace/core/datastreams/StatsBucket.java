package datadog.trace.core.datastreams;

import datadog.trace.bootstrap.instrumentation.api.KafkaOffset;
import datadog.trace.bootstrap.instrumentation.api.StatsPoint;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

public class StatsBucket {
  private final long startTimeNanos;
  private final long bucketDurationNanos;
  private final Map<Long, StatsGroup> hashToGroup = new HashMap<>();
  private final Map<TopicPartition, Long> latestKafkaProduceOffsets = new HashMap<>();
  private final Map<TopicPartitionGroup, Long> latestKafkaCommitOffsets = new HashMap<>();

  public StatsBucket(long startTimeNanos, long bucketDurationNanos) {
    this.startTimeNanos = startTimeNanos;
    this.bucketDurationNanos = bucketDurationNanos;
  }

  public void addPoint(StatsPoint statsPoint) {
    StatsGroup statsGroup = hashToGroup.get(statsPoint.getHash());

    // FIXME Java 7
    if (statsGroup == null) {
      statsGroup =
          new StatsGroup(
              statsPoint.getEdgeTags(), statsPoint.getHash(), statsPoint.getParentHash());
      hashToGroup.put(statsPoint.getHash(), statsGroup);
    }

    statsGroup.add(statsPoint.getPathwayLatencyNano(), statsPoint.getEdgeLatencyNano());
  }

  public void addOffset(KafkaOffset offset) {
    if (offset.getType() == KafkaOffset.Type.COMMIT) {
      latestKafkaCommitOffsets.put(
          new TopicPartitionGroup(offset.getTopic(), offset.getPartition(), offset.getGroup()),
          offset.getOffset());
    } else {
      latestKafkaProduceOffsets.put(
          new TopicPartition(offset.getTopic(), offset.getPartition()), offset.getOffset());
    }
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

  public Collection<Map.Entry<TopicPartitionGroup, Long>> getLatestKafkaCommitOffsets() {
    return latestKafkaCommitOffsets.entrySet();
  }

  public Collection<Map.Entry<TopicPartition, Long>> getLatestKafkaProduceOffsets() {
    return latestKafkaProduceOffsets.entrySet();
  }
}

package datadog.trace.core.datastreams;

import datadog.trace.api.datastreams.Backlog;
import datadog.trace.api.datastreams.DataStreamsTags;
import datadog.trace.api.datastreams.StatsPoint;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

public class StatsBucket {
  private final long startTimeNanos;
  private final long bucketDurationNanos;
  private final Map<Long, StatsGroup> hashToGroup = new HashMap<>();
  private final Map<DataStreamsTags, Long> backlogs = new HashMap<>();

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
}

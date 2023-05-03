package datadog.trace.core.datastreams;

import datadog.trace.bootstrap.instrumentation.api.Backlog;
import datadog.trace.bootstrap.instrumentation.api.StatsPoint;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class StatsBucket {
  private final long startTimeNanos;
  private final long bucketDurationNanos;
  private final Map<Long, StatsGroup> hashToGroup = new HashMap<>();
  private final Map<List<String>, Long> backlogs = new HashMap<>();

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

  public void addBacklog(Backlog backlog) {
    backlogs.compute(
        backlog.getSortedTags(),
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

  public Collection<Map.Entry<List<String>, Long>> getBacklogs() {
    return backlogs.entrySet();
  }
}

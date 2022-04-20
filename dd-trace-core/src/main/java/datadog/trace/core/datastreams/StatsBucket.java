package datadog.trace.core.datastreams;

import datadog.trace.bootstrap.instrumentation.api.StatsPoint;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

public class StatsBucket {
  private final long startTimeNanos;
  private final long bucketDurationNanos;
  private final Map<Long, StatsGroup> hashToGroup = new HashMap<>();

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
              statsPoint.getType(),
              statsPoint.getGroup(),
              statsPoint.getTopic(),
              statsPoint.getHash(),
              statsPoint.getParentHash());
      hashToGroup.put(statsPoint.getHash(), statsGroup);
    }

    statsGroup.add(statsPoint.getPathwayLatencyNano(), statsPoint.getEdgeLatencyNano());
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
}

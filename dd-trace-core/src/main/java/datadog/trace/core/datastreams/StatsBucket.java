package datadog.trace.core.datastreams;

import datadog.trace.bootstrap.instrumentation.api.StatsPoint;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

public class StatsBucket {
  private final long startTime;
  private final long bucketDuration;
  private final Map<Long, StatsGroup> hashToGroup = new HashMap<>();

  public StatsBucket(long startTime, long bucketDurationMillis) {
    this.startTime = startTime;
    this.bucketDuration = bucketDurationMillis;
  }

  public void addPoint(StatsPoint statsPoint) {
    StatsGroup statsGroup = hashToGroup.get(statsPoint.getHash());

    // FIXME Java 7
    if (statsGroup == null) {
      statsGroup = new StatsGroup(statsPoint.getType(), statsPoint.getGroup(), statsPoint.getTopic(), statsPoint.getHash(), statsPoint.getParentHash());
      hashToGroup.put(statsPoint.getHash(), statsGroup);
    }

    statsGroup.add(statsPoint.getPathwayLatencyNano(), statsPoint.getEdgeLatencyNano());
  }

  public long getStartTime() {
    return startTime;
  }

  public long getBucketDuration() {
    return bucketDuration;
  }

  public long getBucketDurationNanos() {
    return bucketDuration * 1000 * 1000;
  }

  public Collection<StatsGroup> getGroups() {
    return hashToGroup.values();
  }
}

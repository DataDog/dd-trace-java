package datadog.trace.core.datastreams;

import datadog.trace.bootstrap.instrumentation.api.Backlog;
import datadog.trace.bootstrap.instrumentation.api.ConsumedThroughput;
import datadog.trace.bootstrap.instrumentation.api.FanOutThroughput;
import datadog.trace.bootstrap.instrumentation.api.GeneratedThroughput;
import datadog.trace.bootstrap.instrumentation.api.ProducedThroughput;
import datadog.trace.bootstrap.instrumentation.api.StatsPoint;
import datadog.trace.bootstrap.instrumentation.api.TerminatedThroughput;
import datadog.trace.bootstrap.instrumentation.api.ThroughputBase;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class StatsBucket {
  private final long startTimeNanos;
  private final long bucketDurationNanos;
  private final Map<Long, StatsGroup> hashToGroup = new HashMap<>();
  private final Map<List<String>, Long> backlogs = new HashMap<>();
  private final Map<Long, Throughput> hashToThroughputsByOrigin = new HashMap<>();
  private final Map<Long, Throughput> hashToThroughputsByEdgeStart = new HashMap<>();
  private final Map<Long, Throughput> hashToThroughputsByServiceStart = new HashMap<>();

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

  private void incrementThroughput(Throughput throughput, ThroughputBase inputThroughput) {
    /*if (inputThroughput instanceof FanInThroughput) {
      throughput.incrementFanIn();
    } else */if (inputThroughput instanceof FanOutThroughput) {
      throughput.incrementFanOut();
    } else if (inputThroughput instanceof TerminatedThroughput) {
      throughput.incrementTerminated();
    } else if (inputThroughput instanceof ProducedThroughput) {
      throughput.incrementProduced();
    } else if (inputThroughput instanceof ConsumedThroughput) {
      throughput.incrementConsumed();
    } else if (inputThroughput instanceof GeneratedThroughput) {
      throughput.incrementGenerated();
    }
  }

  public void incrementThroughputByOrigin(ThroughputBase singleThroughput) {
    Throughput throughput = hashToThroughputsByOrigin.get(singleThroughput.getHash());
    if (throughput == null) {
      throughput =
          new Throughput(singleThroughput.getHash(), TimestampType.TIMESTAMP_ORIGIN);
      hashToThroughputsByOrigin.put(singleThroughput.getHash(), throughput);
    }
    incrementThroughput(throughput, singleThroughput);
  }

  public void incrementThroughputByEdgeStart(ThroughputBase singleThroughput) {
    Throughput throughput = hashToThroughputsByEdgeStart.get(singleThroughput.getHash());
    if (throughput == null) {
      throughput =
          new Throughput(singleThroughput.getHash(), TimestampType.TIMESTAMP_EDGE_START);
      hashToThroughputsByEdgeStart.put(singleThroughput.getHash(), throughput);
    }
    incrementThroughput(throughput, singleThroughput);
  }

  public void incrementThroughputByServiceStart(ThroughputBase singleThroughput) {
    Throughput throughput = hashToThroughputsByServiceStart.get(singleThroughput.getHash());
    if (throughput == null) {
      throughput =
          new Throughput(singleThroughput.getHash(), TimestampType.TIMESTAMP_SERVICE_START);
      hashToThroughputsByServiceStart.put(singleThroughput.getHash(), throughput);
    }
    incrementThroughput(throughput, singleThroughput);
  }
/*
  public void incrementFanIn(long hash) {
    throughput.incrementFanIn();
  }

  public void incrementFanout() {
    throughput.incrementFanOut();
  }

  public void incrementTerminated() {
    throughput.incrementTerminated();
  }

  public void incrementProduced() {
    throughput.incrementProduced();
  }

  public void incrementConsumed() {
    throughput.incrementConsumed();
  }

  public void incrementGenerated() {
    throughput.incrementGenerated();
  }*/

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

  public Collection<Throughput> getThroughputsByOrigin() {
    return hashToThroughputsByOrigin.values();
  }

  public Collection<Throughput> getThroughputsByEdgeStart() {
    return hashToThroughputsByEdgeStart.values();
  }

  public Collection<Throughput> getThroughputsByServiceStart() {
    return hashToThroughputsByServiceStart.values();
  }

  @Override
  public String toString() {
    return "StatsBucket{"
        + "startTimeNanos="
        + startTimeNanos
        + ", bucketDurationNanos="
        + bucketDurationNanos
        + ", hashToGroup="
        + hashToGroup
        + ", backlogs="
        + backlogs
        + ", hashToThroughputsByOrigin="
        + hashToThroughputsByOrigin
        + ", hashToThroughputsByEdgeStart="
        + hashToThroughputsByEdgeStart
        + ", hashToThroughputsByServiceStart="
        + hashToThroughputsByServiceStart
        + '}';
  }
}

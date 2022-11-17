package datadog.trace.core.datastreams;

import datadog.trace.core.histogram.Histogram;
import datadog.trace.core.histogram.Histograms;
import java.util.List;

public class StatsGroup {
  private static final double NANOSECONDS_TO_SECOND = 1_000_000_000d;

  private final List<String> edgeTags;
  private final long hash;
  private final long parentHash;
  private final Histogram pathwayLatency;
  private final Histogram edgeLatency;

  public StatsGroup(List<String> edgeTags, long hash, long parentHash) {
    this.edgeTags = edgeTags;
    this.hash = hash;
    this.parentHash = parentHash;
    pathwayLatency = Histograms.newHistogram();
    edgeLatency = Histograms.newHistogram();
  }

  public void add(long pathwayLatencyNano, long edgeLatencyNano) {
    pathwayLatency.accept(((double) pathwayLatencyNano) / NANOSECONDS_TO_SECOND);
    edgeLatency.accept(((double) edgeLatencyNano) / NANOSECONDS_TO_SECOND);
  }

  public List<String> getEdgeTags() {
    return edgeTags;
  }

  public long getHash() {
    return hash;
  }

  public long getParentHash() {
    return parentHash;
  }

  public Histogram getPathwayLatency() {
    return pathwayLatency;
  }

  public Histogram getEdgeLatency() {
    return edgeLatency;
  }

  @Override
  public String toString() {
    return "StatsGroup{"
        + "edgeTags='"
        + edgeTags
        + '\''
        + ", hash="
        + hash
        + ", parentHash="
        + parentHash
        + '}';
  }
}

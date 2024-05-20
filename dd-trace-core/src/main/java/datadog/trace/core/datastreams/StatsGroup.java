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
  private final Histogram payloadSize;

  public StatsGroup(List<String> edgeTags, long hash, long parentHash) {
    this.edgeTags = edgeTags;
    this.hash = hash;
    this.parentHash = parentHash;
    pathwayLatency = Histograms.newLogHistogram();
    edgeLatency = Histograms.newLogHistogram();
    payloadSize = Histograms.newLogHistogram();
  }

  public void add(long pathwayLatencyNano, long edgeLatencyNano, long payloadSizeBytes) {
    pathwayLatency.accept(((double) pathwayLatencyNano) / NANOSECONDS_TO_SECOND);
    edgeLatency.accept(((double) edgeLatencyNano) / NANOSECONDS_TO_SECOND);
    // payload size is set to zero when we cannot compute it
    // in that case, it's probably better to have an empty histogram than filling it with zeros
    if (payloadSizeBytes != 0) payloadSize.accept((double) payloadSizeBytes);
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

  public Histogram getPayloadSize() {
    return payloadSize;
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

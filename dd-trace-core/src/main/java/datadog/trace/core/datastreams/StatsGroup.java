package datadog.trace.core.datastreams;

import static datadog.metrics.api.DDSketchHistograms.histograms;

import datadog.metrics.api.Histogram;
import datadog.trace.api.datastreams.DataStreamsTags;

public class StatsGroup {
  private static final double NANOSECONDS_TO_SECOND = 1_000_000_000d;

  private final DataStreamsTags tags;
  private final long hash;
  private final long parentHash;
  private final Histogram pathwayLatency;
  private final Histogram edgeLatency;
  private final Histogram payloadSize;

  public StatsGroup(DataStreamsTags tags, long hash, long parentHash) {
    this.tags = tags;
    this.hash = hash;
    this.parentHash = parentHash;
    this.pathwayLatency = histograms().newLogHistogram();
    this.edgeLatency = histograms().newLogHistogram();
    this.payloadSize = histograms().newLogHistogram();
  }

  public void add(long pathwayLatencyNano, long edgeLatencyNano, long payloadSizeBytes) {
    pathwayLatency.accept(((double) pathwayLatencyNano) / NANOSECONDS_TO_SECOND);
    edgeLatency.accept(((double) edgeLatencyNano) / NANOSECONDS_TO_SECOND);
    // payload size is set to zero when we cannot compute it
    // in that case, it's probably better to have an empty histogram than filling it with zeros
    if (payloadSizeBytes != 0) payloadSize.accept((double) payloadSizeBytes);
  }

  public DataStreamsTags getTags() {
    return tags;
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
        + "tags='"
        + tags
        + '\''
        + ", hash="
        + hash
        + ", parentHash="
        + parentHash
        + '}';
  }
}

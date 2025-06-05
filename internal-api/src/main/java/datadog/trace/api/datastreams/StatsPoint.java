package datadog.trace.api.datastreams;

import java.util.List;

public class StatsPoint implements InboxItem {
  private final List<String> edgeTags;
  private final long hash;
  private final long parentHash;
  private final long aggregationHash;
  private final long timestampNanos;
  private final long pathwayLatencyNano;
  private final long edgeLatencyNano;
  private final long payloadSizeBytes;
  private final String serviceNameOverride;

  public StatsPoint(
      List<String> edgeTags,
      long hash,
      long parentHash,
      long aggregationHash,
      long timestampNanos,
      long pathwayLatencyNano,
      long edgeLatencyNano,
      long payloadSizeBytes,
      String serviceNameOverride) {
    this.edgeTags = edgeTags;
    this.hash = hash;
    this.parentHash = parentHash;
    this.aggregationHash = aggregationHash;
    this.timestampNanos = timestampNanos;
    this.pathwayLatencyNano = pathwayLatencyNano;
    this.edgeLatencyNano = edgeLatencyNano;
    this.payloadSizeBytes = payloadSizeBytes;
    this.serviceNameOverride = serviceNameOverride;
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

  public long getTimestampNanos() {
    return timestampNanos;
  }

  public long getPathwayLatencyNano() {
    return pathwayLatencyNano;
  }

  public long getEdgeLatencyNano() {
    return edgeLatencyNano;
  }

  public long getPayloadSizeBytes() {
    return payloadSizeBytes;
  }

  public long getAggregationHash() {
    return aggregationHash;
  }

  public String getServiceNameOverride() {
    return serviceNameOverride;
  }

  @Override
  public String toString() {
    return "StatsPoint{"
        + "tags='"
        + edgeTags
        + '\''
        + ", hash="
        + hash
        + ", parentHash="
        + parentHash
        + ", aggregationHash="
        + aggregationHash
        + ", timestampNanos="
        + timestampNanos
        + ", pathwayLatencyNano="
        + pathwayLatencyNano
        + ", edgeLatencyNano="
        + edgeLatencyNano
        + ", payloadSizeBytes="
        + payloadSizeBytes
        + '}';
  }
}

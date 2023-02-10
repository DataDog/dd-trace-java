package datadog.trace.bootstrap.instrumentation.api;

import java.util.List;

public class StatsPoint implements InboxItem {
  private final List<String> edgeTags;
  private final long hash;
  private final long parentHash;
  private final long timestampNanos;
  private final long pathwayLatencyNano;
  private final long edgeLatencyNano;

  public StatsPoint(
      List<String> edgeTags,
      long hash,
      long parentHash,
      long timestampNanos,
      long pathwayLatencyNano,
      long edgeLatencyNano) {
    this.edgeTags = edgeTags;
    this.hash = hash;
    this.parentHash = parentHash;
    this.timestampNanos = timestampNanos;
    this.pathwayLatencyNano = pathwayLatencyNano;
    this.edgeLatencyNano = edgeLatencyNano;
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
        + ", timestampNanos="
        + timestampNanos
        + ", pathwayLatencyNano="
        + pathwayLatencyNano
        + ", edgeLatencyNano="
        + edgeLatencyNano
        + '}';
  }
}

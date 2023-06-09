package datadog.trace.bootstrap.instrumentation.api;

import java.util.List;

public class StatsPoint implements InboxItem {
  private final List<String> edgeTags;
  private final long hash;
  private final long parentHash;
  private final long timestampNanos;
  private final long pathwayLatencyNano;
  private final long edgeLatencyNano;
  private final boolean fanIn;
  private final boolean fanOut;
  private final boolean dropped;
  private final boolean ignoreLatencies;

  public StatsPoint(
      List<String> edgeTags,
      long hash,
      long parentHash,
      long timestampNanos,
      long pathwayLatencyNano,
      long edgeLatencyNano,
      boolean fanIn,
      boolean fanOut,
      boolean dropped,
      boolean ignoreLatencies) {
    this.edgeTags = edgeTags;
    this.hash = hash;
    this.parentHash = parentHash;
    this.timestampNanos = timestampNanos;
    this.pathwayLatencyNano = pathwayLatencyNano;
    this.edgeLatencyNano = edgeLatencyNano;
    this.fanIn = fanIn;
    this.fanOut = fanOut;
    this.dropped = dropped;
    this.ignoreLatencies = ignoreLatencies;
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

  public boolean getFanIn() { return fanIn; }

  public boolean getFanOut() { return fanOut; }

  public boolean getDropped() { return dropped; }

  public boolean isIgnoreLatencies() { return ignoreLatencies; }

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
        + ", fanIn="
        + fanIn
        + ", fanOut="
        + fanOut
        + ", dropped="
        + dropped
        + ", ignoreLatencies="
        + ignoreLatencies
        + '}';
  }
}

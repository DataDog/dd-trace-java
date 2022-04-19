package datadog.trace.bootstrap.instrumentation.api;

public class StatsPoint {
  private final String type;
  private final String group;
  private final String topic;
  private final long hash;
  private final long parentHash;
  private final long timestampNanos;
  private final long pathwayLatencyNano;
  private final long edgeLatencyNano;

  public StatsPoint(
      String type,
      String group,
      String topic,
      long hash,
      long parentHash,
      long timestampNanos,
      long pathwayLatencyNano,
      long edgeLatencyNano) {
    this.type = type;
    this.group = group;
    this.topic = topic;
    this.hash = hash;
    this.parentHash = parentHash;
    this.timestampNanos = timestampNanos;
    this.pathwayLatencyNano = pathwayLatencyNano;
    this.edgeLatencyNano = edgeLatencyNano;
  }

  public String getType() {
    return type;
  }

  public String getGroup() {
    return group;
  }

  public String getTopic() {
    return topic;
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
        + "type='"
        + type
        + '\''
        + ", group='"
        + group
        + '\''
        + ", topic='"
        + topic
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

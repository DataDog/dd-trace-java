package datadog.trace.bootstrap.instrumentation.api;

public class KafkaOffset implements StatsPayload {
  @Override
  public StatsPayload.Type type() {
    return StatsPayload.Type.KafkaOffset;
  }

  @Override
  public long getTimestampNanos() {
    return timestamp;
  }

  public enum Type {
    COMMIT,
    PRODUCE
  }

  final Type type;

  public Type getType() {
    return this.type;
  }

  public String getGroup() {
    return this.group;
  }

  public String getTopic() {
    return this.topic;
  }

  public int getPartition() {
    return this.partition;
  }

  public long getOffset() {
    return this.offset;
  }

  private final String group;
  private final String topic;
  private final int partition;
  private final long offset;
  private final long timestamp;

  public KafkaOffset(String topic, int partition, long offset, long timestamp) {
    this.type = Type.PRODUCE;
    this.group = null;
    this.topic = topic;
    this.partition = partition;
    this.offset = offset;
    this.timestamp = timestamp;
  }

  public KafkaOffset(String group, String topic, int partition, long offset, long timestamp) {
    this.type = Type.COMMIT;
    this.group = group;
    this.topic = topic;
    this.partition = partition;
    this.offset = offset;
    this.timestamp = timestamp;
  }
}

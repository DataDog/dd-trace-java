package datadog.trace.bootstrap.instrumentation.api;

public interface StatsPayload {
  enum Type {
    StatsGroup,
    KafkaOffset
  }

  public Type type();

  public long getTimestampNanos();
}

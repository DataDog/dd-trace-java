package datadog.trace.instrumentation.kafka_common;

import java.util.Map;

/** Holds pending Kafka config info until the client's connection lifecycle resolves. */
public class PendingConfig {
  public static final String STATUS_CONNECTED = "connected";
  public static final String STATUS_FAILED = "failed";

  public final String type;
  public final String consumerGroup;
  public final Map<String, String> config;

  public PendingConfig(String type, String consumerGroup, Map<String, String> config) {
    this.type = type;
    this.consumerGroup = consumerGroup;
    this.config = config;
  }
}

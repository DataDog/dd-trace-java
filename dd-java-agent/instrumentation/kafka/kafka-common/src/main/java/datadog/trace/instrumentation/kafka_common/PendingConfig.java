package datadog.trace.instrumentation.kafka_common;

import java.util.Map;

/** Holds pending Kafka config info until the cluster ID becomes available from metadata. */
public class PendingConfig {
  public final String type;
  public final String consumerGroup;
  public final Map<String, String> config;

  public PendingConfig(String type, String consumerGroup, Map<String, String> config) {
    this.type = type;
    this.consumerGroup = consumerGroup;
    this.config = config;
  }
}

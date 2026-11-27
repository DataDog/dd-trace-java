package datadog.trace.instrumentation.kafka_common;

import java.util.Map;
import org.apache.kafka.common.config.AbstractConfig;

/**
 * Holds pending Kafka config info until the cluster ID becomes available from metadata. The config
 * is converted to strings only when reported, so clients built with DSM off keep no converted copy.
 */
public class PendingConfig {
  public final String type;
  public final String consumerGroup;

  /** Client config built by Kafka, or null when only the raw map is available. */
  public final AbstractConfig clientConfig;

  /**
   * Shallow copy of the caller's raw config map, for Kafka 2.7 consumers that never expose a
   * ConsumerConfig.
   */
  public final Map<String, Object> rawConfig;

  public PendingConfig(String type, String consumerGroup, AbstractConfig clientConfig) {
    this.type = type;
    this.consumerGroup = consumerGroup;
    this.clientConfig = clientConfig;
    this.rawConfig = null;
  }

  public PendingConfig(String type, String consumerGroup, Map<String, Object> rawConfig) {
    this.type = type;
    this.consumerGroup = consumerGroup;
    this.clientConfig = null;
    this.rawConfig = rawConfig;
  }
}

package datadog.trace.instrumentation.kafka_common;

import java.util.Map;
import org.apache.kafka.common.config.AbstractConfig;

/** Holds pending Kafka config info until the cluster ID becomes available from metadata. */
public class PendingConfig {
  public final String type;
  public final String consumerGroup;

  /** Client config converted only when reported, so clients built with DSM off keep no copy. */
  public final AbstractConfig clientConfig;

  /** Already converted config, used when the client was built from a raw map. */
  public final Map<String, String> config;

  public PendingConfig(String type, String consumerGroup, AbstractConfig clientConfig) {
    this.type = type;
    this.consumerGroup = consumerGroup;
    this.clientConfig = clientConfig;
    this.config = null;
  }

  public PendingConfig(String type, String consumerGroup, Map<String, String> config) {
    this.type = type;
    this.consumerGroup = consumerGroup;
    this.clientConfig = null;
    this.config = config;
  }
}

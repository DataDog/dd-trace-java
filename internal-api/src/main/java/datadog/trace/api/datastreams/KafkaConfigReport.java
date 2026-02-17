package datadog.trace.api.datastreams;

import java.util.Map;
import java.util.Objects;

/**
 * KafkaConfigReport captures Kafka producer or consumer configuration to be sent via the Data
 * Streams payload. Each unique configuration is sent only once.
 */
public class KafkaConfigReport implements InboxItem {
  private final String type; // "kafka_producer" or "kafka_consumer"
  private final String kafkaClusterId;
  private final String topic;
  private final String consumerGroup;
  private final Map<String, String> config;
  private final long timestampNanos;
  private final String serviceNameOverride;

  public KafkaConfigReport(
      String type,
      String kafkaClusterId,
      String topic,
      String consumerGroup,
      Map<String, String> config,
      long timestampNanos,
      String serviceNameOverride) {
    this.type = type;
    this.kafkaClusterId = kafkaClusterId != null ? kafkaClusterId : "";
    this.topic = topic != null ? topic : "";
    this.consumerGroup = consumerGroup != null ? consumerGroup : "";
    this.config = config;
    this.timestampNanos = timestampNanos;
    this.serviceNameOverride = serviceNameOverride;
  }

  public String getType() {
    return type;
  }

  public String getKafkaClusterId() {
    return kafkaClusterId;
  }

  public String getTopic() {
    return topic;
  }

  public String getConsumerGroup() {
    return consumerGroup;
  }

  public Map<String, String> getConfig() {
    return config;
  }

  public long getTimestampNanos() {
    return timestampNanos;
  }

  public String getServiceNameOverride() {
    return serviceNameOverride;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    KafkaConfigReport that = (KafkaConfigReport) o;
    return Objects.equals(type, that.type)
        && Objects.equals(kafkaClusterId, that.kafkaClusterId)
        && Objects.equals(topic, that.topic)
        && Objects.equals(consumerGroup, that.consumerGroup)
        && Objects.equals(config, that.config);
  }

  @Override
  public int hashCode() {
    int result = type != null ? type.hashCode() : 0;
    result = 31 * result + (kafkaClusterId != null ? kafkaClusterId.hashCode() : 0);
    result = 31 * result + (topic != null ? topic.hashCode() : 0);
    result = 31 * result + (consumerGroup != null ? consumerGroup.hashCode() : 0);
    result = 31 * result + (config != null ? config.hashCode() : 0);
    return result;
  }
}

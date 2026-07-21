package datadog.trace.api.datastreams;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * KafkaConfigReport captures Kafka producer or consumer configuration to be sent via the Data
 * Streams payload. Each unique configuration is sent only once.
 */
public class KafkaConfigReport implements InboxItem {
  private static final int NO_GENERATION = -1;

  private final String type;
  private final String kafkaClusterId;
  private final String consumerGroup;
  private final String memberId;
  private final int generationId;
  private final String memberProtocol;
  private final Map<String, String> config;
  private final long timestampNanos;
  private final String serviceNameOverride;

  public KafkaConfigReport(
      String type,
      String kafkaClusterId,
      String consumerGroup,
      Map<String, String> config,
      long timestampNanos,
      String serviceNameOverride) {
    this(
        type,
        kafkaClusterId,
        consumerGroup,
        "",
        NO_GENERATION,
        "",
        config,
        timestampNanos,
        serviceNameOverride);
  }

  public KafkaConfigReport(
      String type,
      String kafkaClusterId,
      String consumerGroup,
      String memberId,
      int generationId,
      String memberProtocol,
      Map<String, String> config,
      long timestampNanos,
      String serviceNameOverride) {
    this.type = type;
    this.kafkaClusterId = kafkaClusterId != null ? kafkaClusterId : "";
    this.consumerGroup = consumerGroup != null ? consumerGroup : "";
    this.memberId = memberId != null ? memberId : "";
    this.generationId = generationId;
    this.memberProtocol = memberProtocol != null ? memberProtocol : "";
    this.config = config != null ? config : Collections.<String, String>emptyMap();
    this.timestampNanos = timestampNanos;
    this.serviceNameOverride = serviceNameOverride;
  }

  public String getType() {
    return type;
  }

  public String getKafkaClusterId() {
    return kafkaClusterId;
  }

  public String getConsumerGroup() {
    return consumerGroup;
  }

  public String getMemberId() {
    return memberId;
  }

  public int getGenerationId() {
    return generationId;
  }

  public String getMemberProtocol() {
    return memberProtocol;
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
    return generationId == that.generationId
        && Objects.equals(type, that.type)
        && Objects.equals(kafkaClusterId, that.kafkaClusterId)
        && Objects.equals(consumerGroup, that.consumerGroup)
        && Objects.equals(memberId, that.memberId)
        && Objects.equals(memberProtocol, that.memberProtocol)
        && Objects.equals(config, that.config);
  }

  @Override
  public int hashCode() {
    int result = type != null ? type.hashCode() : 0;
    result = 31 * result + (kafkaClusterId != null ? kafkaClusterId.hashCode() : 0);
    result = 31 * result + (consumerGroup != null ? consumerGroup.hashCode() : 0);
    result = 31 * result + (memberId != null ? memberId.hashCode() : 0);
    result = 31 * result + generationId;
    result = 31 * result + (memberProtocol != null ? memberProtocol.hashCode() : 0);
    result = 31 * result + (config != null ? config.hashCode() : 0);
    return result;
  }
}

package datadog.trace.instrumentation.kafka_clients;

import java.util.Objects;
import javax.annotation.Nullable;
import org.apache.kafka.clients.Metadata;

public class KafkaConsumerInfo {
  private final String consumerGroup;
  private final Metadata clientMetadata;

  public KafkaConsumerInfo(String consumerGroup, Metadata clientMetadata) {
    this.consumerGroup = consumerGroup;
    this.clientMetadata = clientMetadata;
  }

  public KafkaConsumerInfo(String consumerGroup) {
    this.consumerGroup = consumerGroup;
    this.clientMetadata = null;
  }

  @Nullable
  public String getConsumerGroup() {
    return consumerGroup;
  }

  @Nullable
  public Metadata getClientMetadata() {
    return clientMetadata;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    KafkaConsumerInfo consumerInfo = (KafkaConsumerInfo) o;
    return Objects.equals(consumerGroup, consumerInfo.consumerGroup)
        && Objects.equals(clientMetadata, consumerInfo.clientMetadata);
  }

  @Override
  public int hashCode() {
    return 31 * (null == consumerGroup ? 0 : consumerGroup.hashCode())
        + (null == clientMetadata ? 0 : clientMetadata.hashCode());
  }
}

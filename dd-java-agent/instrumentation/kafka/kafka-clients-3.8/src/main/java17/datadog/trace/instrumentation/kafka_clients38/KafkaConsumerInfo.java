package datadog.trace.instrumentation.kafka_clients38;

import java.util.Objects;
import java.util.Optional;
import org.apache.kafka.clients.Metadata;

public class KafkaConsumerInfo {
  private final String consumerGroup;
  private final Metadata metadata;
  private final String bootstrapServers;

  public KafkaConsumerInfo(String consumerGroup, Metadata metadata, String bootstrapServers) {
    this.consumerGroup = consumerGroup;
    this.metadata = metadata;
    this.bootstrapServers = bootstrapServers;
  }

  public KafkaConsumerInfo(String consumerGroup, String bootstrapServers) {
    this.consumerGroup = consumerGroup;
    this.metadata = null;
    this.bootstrapServers = bootstrapServers;
  }

  public Optional<String> getConsumerGroup() {
    return Optional.ofNullable(consumerGroup);
  }

  public Optional<Metadata> getmetadata() {
    return Optional.ofNullable(metadata);
  }

  public Optional<String> getBootstrapServers() {
    return Optional.ofNullable(bootstrapServers);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    KafkaConsumerInfo consumerInfo = (KafkaConsumerInfo) o;
    return Objects.equals(consumerGroup, consumerInfo.consumerGroup)
        && Objects.equals(metadata, consumerInfo.metadata);
  }

  @Override
  public int hashCode() {
    return 31 * (null == consumerGroup ? 0 : consumerGroup.hashCode())
        + (null == metadata ? 0 : metadata.hashCode());
  }
}

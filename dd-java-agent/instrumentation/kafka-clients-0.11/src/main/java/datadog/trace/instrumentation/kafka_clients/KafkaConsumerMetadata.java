package datadog.trace.instrumentation.kafka_clients;

import java.util.Objects;
import javax.annotation.Nullable;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.internals.ConsumerMetadata;

public class KafkaConsumerMetadata {
  private final String consumerGroup;
  private final ConsumerMetadata consumerMetadata;

  public KafkaConsumerMetadata(String consumerGroup, ConsumerMetadata consumerMetadata) {
    this.consumerGroup = consumerGroup;
    this.consumerMetadata = consumerMetadata;
  }

  @Nullable
  public String getConsumerGroup() {
    return consumerGroup;
  }

  @Nullable
  public ConsumerMetadata getConsumerMetadata() {
    return consumerMetadata;
  }

  public static class Builder {
    private String consumerGroup;
    private ConsumerMetadata consumerMetadata;

    public Builder() {}

    public Builder consumerGroup(String consumerGroup) {
      this.consumerGroup = consumerGroup;
      return this;
    }

    public Builder consumerMetadata(ConsumerMetadata consumerMetadata) {
      this.consumerMetadata = consumerMetadata;
      return this;
    }

    public KafkaConsumerMetadata build() {
      return new KafkaConsumerMetadata(consumerGroup, consumerMetadata);
    }
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    KafkaConsumerMetadata metadata = (KafkaConsumerMetadata) o;
    return Objects.equals(consumerGroup, metadata.consumerGroup)
        && Objects.equals(consumerMetadata, metadata.consumerMetadata);
  }

  @Override
  public int hashCode() {
    return Objects.hash(consumerGroup, consumerMetadata);
  }
}

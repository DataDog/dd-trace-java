package datadog.trace.instrumentation.kafka_clients;

import java.util.Objects;
import javax.annotation.Nullable;

public class KafkaConsumerMetadata {
  private final String consumerGroup;
  private final String clusterId;

  public KafkaConsumerMetadata(String consumerGroup, String clusterId) {
    this.consumerGroup = consumerGroup;
    this.clusterId = clusterId;
  }

  @Nullable
  public String getConsumerGroup() {
    return consumerGroup;
  }

  @Nullable
  public String getClusterId() {
    return clusterId;
  }
/*
  public static class Builder {
    private String consumerGroup;
    private String clusterId;

    Builder() {}

    public Builder consumerGroup(String consumerGroup) {
      this.consumerGroup = consumerGroup;
      return this;
    }

    public Builder clusterId(String clusterId) {
      this.clusterId = clusterId;
      return this;
    }

    public KafkaConsumerMetadata build() {
      return new KafkaConsumerMetadata(consumerGroup, clusterId);
    }
  }
*/

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    KafkaConsumerMetadata metadata = (KafkaConsumerMetadata) o;
    return Objects.equals(consumerGroup, metadata.consumerGroup)
        && Objects.equals(clusterId, metadata.clusterId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(consumerGroup, clusterId);
  }
}

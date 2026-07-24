package datadog.trace.instrumentation.kafka_clients;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;
import org.apache.kafka.clients.Metadata;

public class KafkaConsumerInfo {
  private final String consumerGroup;
  private final Metadata clientMetadata;
  private final String bootstrapServers;

  // handle to the consume span this consumer left lingering past its last poll loop; not part of
  // consumer identity, so excluded from equals/hashCode
  private final AtomicReference<AgentSpan> deferredConsumeSpan = new AtomicReference<>();

  public KafkaConsumerInfo(String consumerGroup, Metadata clientMetadata, String bootstrapServers) {
    this.consumerGroup = consumerGroup;
    this.clientMetadata = clientMetadata;
    this.bootstrapServers = bootstrapServers;
  }

  public KafkaConsumerInfo(String consumerGroup, String bootstrapServers) {
    this.consumerGroup = consumerGroup;
    this.clientMetadata = null;
    this.bootstrapServers = bootstrapServers;
  }

  public void setDeferredConsumeSpan(AgentSpan span) {
    deferredConsumeSpan.set(span);
  }

  public AgentSpan getAndClearDeferredConsumeSpan() {
    return deferredConsumeSpan.getAndSet(null);
  }

  @Nullable
  public String getConsumerGroup() {
    return consumerGroup;
  }

  @Nullable
  public Metadata getClientMetadata() {
    return clientMetadata;
  }

  @Nullable
  public String getBootstrapServers() {
    return bootstrapServers;
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

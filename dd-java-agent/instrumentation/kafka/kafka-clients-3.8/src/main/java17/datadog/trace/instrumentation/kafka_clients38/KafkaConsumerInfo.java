package datadog.trace.instrumentation.kafka_clients38;

import java.util.Objects;
import java.util.Optional;
import org.apache.kafka.clients.Metadata;

public class KafkaConsumerInfo {
  private final String consumerGroup;
  private final Metadata metadata;
  private final String bootstrapServers;
  private final boolean clusterIdForDataStreamsOnly;
  private volatile String lastReportedMemberId;
  private volatile int lastReportedGenerationId = Integer.MIN_VALUE;

  public KafkaConsumerInfo(String consumerGroup, Metadata metadata, String bootstrapServers) {
    this(consumerGroup, metadata, bootstrapServers, false);
  }

  /**
   * Creates a consumer info whose metadata may be kept only for Data Streams Monitoring.
   *
   * @param clusterIdForDataStreamsOnly whether the metadata cluster ID is used only while Data
   *     Streams Monitoring is enabled
   */
  public KafkaConsumerInfo(
      String consumerGroup,
      Metadata metadata,
      String bootstrapServers,
      boolean clusterIdForDataStreamsOnly) {
    this.consumerGroup = consumerGroup;
    this.metadata = metadata;
    this.bootstrapServers = bootstrapServers;
    this.clusterIdForDataStreamsOnly = clusterIdForDataStreamsOnly;
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

  public boolean isClusterIdForDataStreamsOnly() {
    return clusterIdForDataStreamsOnly;
  }

  public boolean hasMembershipChanged(String memberId, int generationId) {
    return !Objects.equals(memberId, lastReportedMemberId)
        || generationId != lastReportedGenerationId;
  }

  public void setLastReportedMembership(String memberId, int generationId) {
    this.lastReportedMemberId = memberId;
    this.lastReportedGenerationId = generationId;
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

package datadog.trace.instrumentation.kafka_clients38;

import datadog.trace.bootstrap.ContextStore;
import datadog.trace.instrumentation.kafka_common.MetadataState;
import java.util.Optional;
import org.apache.kafka.clients.Metadata;

public class KafkaConsumerInstrumentationHelper {
  public static String extractGroup(KafkaConsumerInfo kafkaConsumerInfo) {
    if (kafkaConsumerInfo != null) {
      return kafkaConsumerInfo.getConsumerGroup().orElse(null);
    }
    return null;
  }

  public static String extractClusterId(
      KafkaConsumerInfo kafkaConsumerInfo,
      ContextStore<Metadata, MetadataState> metadataContextStore) {
    if (kafkaConsumerInfo != null) {
      Optional<Metadata> metadata = kafkaConsumerInfo.getmetadata();
      if (metadata.isPresent()) {
        MetadataState state = metadataContextStore.get(metadata.get());
        return state != null ? state.clusterId : null;
      }
    }
    return null;
  }

  public static String extractBootstrapServers(KafkaConsumerInfo kafkaConsumerInfo) {
    return kafkaConsumerInfo == null ? null : kafkaConsumerInfo.getBootstrapServers().orElse(null);
  }
}

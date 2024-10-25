package datadog.trace.instrumentation.kafka_clients38;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.ContextStore;
import org.apache.kafka.clients.Metadata;

public class KafkaConsumerInstrumentationHelper {
  public static String extractGroup(KafkaConsumerInfo kafkaConsumerInfo) {
    if (kafkaConsumerInfo != null) {
      return kafkaConsumerInfo.getConsumerGroup().get();
    }
    return null;
  }

  public static String extractClusterId(
      KafkaConsumerInfo kafkaConsumerInfo, ContextStore<Metadata, String> metadataContextStore) {
    if (Config.get().isDataStreamsEnabled() && kafkaConsumerInfo != null) {
      Metadata metadata = kafkaConsumerInfo.getmetadata().get();
      if (metadata != null) {
        return metadataContextStore.get(metadata);
      }
    }
    return null;
  }

  public static String extractBootstrapServers(KafkaConsumerInfo kafkaConsumerInfo) {
    return kafkaConsumerInfo == null ? null : kafkaConsumerInfo.getBootstrapServers().get();
  }
}

package datadog.trace.instrumentation.kafka_clients;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.ContextStore;
import org.apache.kafka.clients.Metadata;

public class KafkaConsumerInstrumentationHelper {
  public static String extractGroup(KafkaConsumerInfo kafkaConsumerInfo) {
    if (kafkaConsumerInfo != null) {
      return kafkaConsumerInfo.getConsumerGroup();
    }
    return null;
  }

  public static String extractClusterId(
      KafkaConsumerInfo kafkaConsumerInfo, ContextStore<Metadata, String> metadataContextStore) {
    if (Config.get().isDataStreamsEnabled() && kafkaConsumerInfo != null) {
      Metadata consumerMetadata = kafkaConsumerInfo.getClientMetadata();
      if (consumerMetadata != null) {
        return metadataContextStore.get(consumerMetadata);
      }
    }
    return null;
  }

  public static String extractBootstrapServers(KafkaConsumerInfo kafkaConsumerInfo) {
    return kafkaConsumerInfo == null ? null : kafkaConsumerInfo.getBootstrapServers();
  }
}

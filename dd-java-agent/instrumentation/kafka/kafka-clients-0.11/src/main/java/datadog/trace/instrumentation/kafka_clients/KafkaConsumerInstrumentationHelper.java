package datadog.trace.instrumentation.kafka_clients;

import datadog.trace.bootstrap.ContextStore;
import datadog.trace.instrumentation.kafka_common.MetadataState;
import org.apache.kafka.clients.Metadata;

public class KafkaConsumerInstrumentationHelper {
  public static String extractGroup(KafkaConsumerInfo kafkaConsumerInfo) {
    if (kafkaConsumerInfo != null) {
      return kafkaConsumerInfo.getConsumerGroup();
    }
    return null;
  }

  public static String extractClusterId(
      KafkaConsumerInfo kafkaConsumerInfo,
      ContextStore<Metadata, MetadataState> metadataContextStore) {
    if (kafkaConsumerInfo != null) {
      Metadata consumerMetadata = kafkaConsumerInfo.getClientMetadata();
      if (consumerMetadata != null) {
        MetadataState state = metadataContextStore.get(consumerMetadata);
        return state != null ? state.clusterId : null;
      }
    }
    return null;
  }

  public static String extractBootstrapServers(KafkaConsumerInfo kafkaConsumerInfo) {
    return kafkaConsumerInfo == null ? null : kafkaConsumerInfo.getBootstrapServers();
  }
}

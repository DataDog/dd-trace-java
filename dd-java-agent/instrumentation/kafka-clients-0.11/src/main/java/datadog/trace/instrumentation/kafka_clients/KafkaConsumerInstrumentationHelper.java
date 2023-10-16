package datadog.trace.instrumentation.kafka_clients;

import datadog.trace.api.Pair;
import datadog.trace.bootstrap.ContextStore;
import org.apache.kafka.clients.Metadata;
import org.apache.kafka.clients.consumer.ConsumerRecords;

public class KafkaConsumerInstrumentationHelper {
  public static Pair<String, String> helper(
      ConsumerRecords records,
      ContextStore<ConsumerRecords, KafkaConsumerInfo> consumerInfoContextStore,
      ContextStore<Metadata, String> metadataContextStore) {
    String group = null;
    String clusterId = null;
    KafkaConsumerInfo kafkaConsumerInfo = consumerInfoContextStore.get(records);
    if (kafkaConsumerInfo != null) {
      group = kafkaConsumerInfo.getConsumerGroup();
      Metadata consumerMetadata = kafkaConsumerInfo.getClientMetadata();
      if (consumerMetadata != null) {
        clusterId = metadataContextStore.get(consumerMetadata);
      }
    }
    return Pair.of(group, clusterId);
  }
}

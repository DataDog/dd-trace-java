package datadog.trace.instrumentation.kafka_clients;

import datadog.trace.bootstrap.ContextStore;
import datadog.trace.instrumentation.kafka_common.MetadataState;
import java.util.Collection;
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

  /**
   * Formats a {@code bootstrap.servers} value from a config map, which may be a comma-separated
   * {@code String} or a {@code List} (as Spring Boot passes it), the way {@code ConsumerConfig}
   * lists are joined: with {@code ","}.
   */
  public static String bootstrapServers(Object configValue) {
    if (configValue instanceof String) {
      String servers = (String) configValue;
      return servers.isEmpty() ? null : servers;
    }
    if (configValue instanceof Collection) {
      StringBuilder servers = new StringBuilder();
      for (Object server : (Collection<?>) configValue) {
        if (server != null) {
          if (servers.length() > 0) {
            servers.append(',');
          }
          servers.append(server);
        }
      }
      return servers.length() == 0 ? null : servers.toString();
    }
    return null;
  }
}

package datadog.trace.instrumentation.kafka_common;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Helper to extract Kafka producer/consumer configuration as string maps for DSM reporting. */
public class KafkaConfigHelper {
  private static final Logger log = LoggerFactory.getLogger(KafkaConfigHelper.class);

  /** Config keys that must never be captured because they may contain secrets. */
  static final Set<String> SENSITIVE_KEYS =
      new HashSet<>(
          Arrays.asList(
              "ssl.keystore.password",
              "ssl.key.password",
              "ssl.truststore.password",
              "ssl.keystore.key",
              "ssl.keystore.certificate.chain",
              "ssl.truststore.certificates",
              "sasl.jaas.config",
              "sasl.oauthbearer.client.credentials.client.secret",
              "sasl.oauthbearer.assertion.private.key.passphrase",
              "sasl.oauthbearer.assertion.private.key.file",
              "sasl.oauthbearer.assertion.file"));

  /** Holds pending Kafka config info until the cluster ID becomes available from metadata. */
  private static class PendingConfig {
    final String type;
    final String consumerGroup;
    final Map<String, String> config;

    PendingConfig(String type, String consumerGroup, Map<String, String> config) {
      this.type = type;
      this.consumerGroup = consumerGroup;
      this.config = config;
    }
  }

  /**
   * Stores pending configs keyed by the Metadata instance. When the metadata update fires with the
   * cluster ID, the config is reported and removed from this map.
   */
  private static final ConcurrentHashMap<Object, PendingConfig> pendingConfigs =
      new ConcurrentHashMap<>();

  /** Store a producer config to be reported once the cluster ID is known from metadata. */
  public static void storePendingProducerConfig(Object metadataKey, Map<String, String> config) {
    pendingConfigs.put(metadataKey, new PendingConfig("producer", "", config));
    log.debug("Stored pending producer config (cluster ID not yet known)");
  }

  /** Store a consumer config to be reported once the cluster ID is known from metadata. */
  public static void storePendingConsumerConfig(
      Object metadataKey, String consumerGroup, Map<String, String> config) {
    pendingConfigs.put(
        metadataKey,
        new PendingConfig("consumer", consumerGroup != null ? consumerGroup : "", config));
    log.debug("Stored pending consumer config (cluster ID not yet known)");
  }

  /** Called from metadata update advice when the cluster ID becomes available. */
  public static void reportPendingConfig(Object metadataKey, String clusterId) {
    PendingConfig pending = pendingConfigs.remove(metadataKey);
    if (pending != null) {
      log.debug("Received cluster ID, reporting {} config", pending.type);
      if (Config.get().isDataStreamsEnabled()) {
        AgentTracer.get()
            .getDataStreamsMonitoring()
            .reportKafkaConfig(pending.type, clusterId, pending.consumerGroup, pending.config);
      }
    }
  }

  public static Map<String, String> extractProducerConfig(ProducerConfig producerConfig) {
    try {
      return convertToStringMap(producerConfig.values());
    } catch (Exception e) {
      log.debug("Error extracting producer configuration", e);
      return new LinkedHashMap<>();
    }
  }

  public static Map<String, String> extractConsumerConfig(ConsumerConfig consumerConfig) {
    try {
      return convertToStringMap(consumerConfig.values());
    } catch (Exception e) {
      log.debug("Error extracting consumer configuration", e);
      return new LinkedHashMap<>();
    }
  }

  public static Map<String, String> extractConsumerConfigFromMap(
      Map<String, Object> consumerConfig) {
    try {
      return convertToStringMap(consumerConfig);
    } catch (Exception e) {
      log.debug("Error extracting consumer configuration from map", e);
      return new LinkedHashMap<>();
    }
  }

  private static Map<String, String> convertToStringMap(Map<String, ?> config) {
    Map<String, String> result = new LinkedHashMap<>();
    for (Map.Entry<String, ?> entry : config.entrySet()) {
      if (SENSITIVE_KEYS.contains(entry.getKey())) {
        continue;
      }
      Object value = entry.getValue();
      result.put(entry.getKey(), value != null ? String.valueOf(value) : "");
    }
    return result;
  }
}

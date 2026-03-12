package datadog.trace.instrumentation.kafka_common;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Helper to extract Kafka producer/consumer configuration as string maps for DSM reporting. */
public class KafkaConfigHelper {
  private static final Logger log = LoggerFactory.getLogger(KafkaConfigHelper.class);

  static final String MASKED_VALUE = "****";

  /** Config keys that are safe to capture with their values. Other keys are captured with masked values. */
  static final Set<String> ALLOWED_KEYS =
      new HashSet<>(
          Arrays.asList(
              // Common client configs
              "bootstrap.servers",
              "client.id",
              "client.dns.lookup",
              "client.rack",
              "metadata.max.age.ms",
              "metadata.max.idle.ms",
              "request.timeout.ms",
              "connections.max.idle.ms",
              "reconnect.backoff.ms",
              "reconnect.backoff.max.ms",
              "retry.backoff.ms",
              "retry.backoff.max.ms",
              "send.buffer.bytes",
              "receive.buffer.bytes",
              "socket.connection.setup.timeout.ms",
              "socket.connection.setup.timeout.max.ms",
              "security.protocol",
              "metrics.sample.window.ms",
              "metrics.num.samples",
              "metrics.recording.level",
              // Producer configs
              "batch.size",
              "acks",
              "linger.ms",
              "buffer.memory",
              "max.request.size",
              "max.block.ms",
              "compression.type",
              "delivery.timeout.ms",
              "enable.idempotence",
              "max.in.flight.requests.per.connection",
              "transaction.timeout.ms",
              "transactional.id",
              "retries",
              "partitioner.class",
              "partitioner.ignore.keys",
              "partitioner.adaptive.partitioning.enable",
              "partitioner.availability.timeout.ms",
              "key.serializer",
              "value.serializer",
              // Consumer configs
              "group.id",
              "group.instance.id",
              "group.protocol",
              "group.remote.assignor",
              "max.poll.records",
              "max.poll.interval.ms",
              "session.timeout.ms",
              "heartbeat.interval.ms",
              "enable.auto.commit",
              "auto.commit.interval.ms",
              "auto.offset.reset",
              "partition.assignment.strategy",
              "fetch.min.bytes",
              "fetch.max.bytes",
              "fetch.max.wait.ms",
              "max.partition.fetch.bytes",
              "check.crcs",
              "key.deserializer",
              "value.deserializer",
              "exclude.internal.topics",
              "isolation.level",
              "allow.auto.create.topics",
              "default.api.timeout.ms"));

  /** Store a producer config to be reported once the cluster ID is known from metadata. */
  public static void storePendingProducerConfig(
      MetadataState state, Map<String, String> config) {
    state.setPendingConfig(new PendingConfig("producer", "", config));
    log.debug("Stored pending producer config (cluster ID not yet known)");
  }

  /** Store a consumer config to be reported once the cluster ID is known from metadata. */
  public static void storePendingConsumerConfig(
      MetadataState state, String consumerGroup, Map<String, String> config) {
    state.setPendingConfig(
        new PendingConfig("consumer", consumerGroup != null ? consumerGroup : "", config));
    log.debug("Stored pending consumer config (cluster ID not yet known)");
  }

  /** Called from metadata update advice when the cluster ID becomes available. */
  public static void reportPendingConfig(MetadataState state, String clusterId) {
    PendingConfig pending = state.takePendingConfig();
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
      if (ALLOWED_KEYS.contains(entry.getKey())) {
        Object value = entry.getValue();
        result.put(entry.getKey(), value != null ? String.valueOf(value) : "");
      } else {
        result.put(entry.getKey(), MASKED_VALUE);
      }
    }
    return result;
  }
}

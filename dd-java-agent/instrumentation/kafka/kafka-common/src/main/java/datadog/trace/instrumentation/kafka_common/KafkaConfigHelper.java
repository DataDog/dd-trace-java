package datadog.trace.instrumentation.kafka_common;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.traceConfig;

import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.config.AbstractConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Helper to extract Kafka producer/consumer configuration as string maps for DSM reporting. */
public class KafkaConfigHelper {
  private static final Logger log = LoggerFactory.getLogger(KafkaConfigHelper.class);

  public static final String MASKED_VALUE = "****";

  /**
   * Config keys that are safe to capture with their values. Other keys are captured with masked
   * values.
   */
  public static final Set<String> ALLOWED_KEYS =
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
      MetadataState state, ProducerConfig producerConfig) {
    state.setPendingConfig(new PendingConfig("kafka_producer", "", producerConfig));
    log.debug("Stored pending producer config (cluster ID not yet known)");
  }

  /** Store a consumer config to be reported once the cluster ID is known from metadata. */
  public static void storePendingConsumerConfig(
      MetadataState state, String consumerGroup, ConsumerConfig consumerConfig) {
    state.setPendingConfig(
        new PendingConfig("kafka_consumer", groupOrEmpty(consumerGroup), consumerConfig));
    log.debug("Stored pending consumer config (cluster ID not yet known)");
  }

  /**
   * Store a shallow copy of a raw consumer config map, converted only when reported, unless a
   * config is already pending.
   */
  public static void storePendingConsumerConfigIfAbsent(
      MetadataState state, String consumerGroup, Map<String, Object> rawConfig) {
    if (state.hasPendingConfig()) {
      return;
    }
    // Copied so later caller mutations aren't reported nor race with reporting threads
    Map<String, Object> rawConfigCopy = new HashMap<>(rawConfig);
    if (state.setPendingConfigIfAbsent(
        new PendingConfig("kafka_consumer", groupOrEmpty(consumerGroup), rawConfigCopy))) {
      log.debug("Stored pending raw consumer config (cluster ID not yet known)");
    }
  }

  private static String groupOrEmpty(String consumerGroup) {
    return consumerGroup != null ? consumerGroup : "";
  }

  /**
   * Reports a consumer group membership, or keeps it pending until Data Streams is enabled and the
   * cluster ID is known.
   *
   * @return whether the membership was reported or kept pending
   */
  public static boolean reportConsumerGroupMember(
      MetadataState state,
      String consumerGroup,
      String memberId,
      int generationId,
      String memberProtocol) {
    if (state == null || memberId == null || memberId.isEmpty()) {
      return false;
    }
    String group = groupOrEmpty(consumerGroup);
    String protocol = memberProtocol != null ? memberProtocol : "";
    String clusterId = state.clusterId;
    if (!canReportConsumerGroupMember(clusterId)) {
      // Reported on the first metadata update with a cluster ID while DSM is enabled
      state.setPendingConsumerGroupMember(
          new PendingConsumerGroupMember(group, memberId, generationId, protocol));
      // Re-checked in case a concurrent metadata update ran before the member was pending
      clusterId = state.clusterId;
      if (canReportConsumerGroupMember(clusterId)) {
        reportPendingConsumerGroupMember(state, clusterId);
      }
      return true;
    }
    // Best effort: drops a pending older generation so it isn't reported after this one
    state.takePendingConsumerGroupMember();
    AgentTracer.get()
        .getDataStreamsMonitoring()
        .reportKafkaConsumerGroupMember(clusterId, group, memberId, generationId, protocol);
    return true;
  }

  private static boolean canReportConsumerGroupMember(String clusterId) {
    return clusterId != null && !clusterId.isEmpty() && traceConfig().isDataStreamsEnabled();
  }

  private static void reportPendingConsumerGroupMember(MetadataState state, String clusterId) {
    PendingConsumerGroupMember member = state.takePendingConsumerGroupMember();
    if (member != null) {
      AgentTracer.get()
          .getDataStreamsMonitoring()
          .reportKafkaConsumerGroupMember(
              clusterId,
              member.consumerGroup,
              member.memberId,
              member.generationId,
              member.memberProtocol);
    }
  }

  /** Called from metadata update advice when the cluster ID becomes available. */
  public static void reportPendingConfig(MetadataState state, String clusterId) {
    if (!traceConfig().isDataStreamsEnabled()) {
      // Kept pending: reported on the first metadata update after DSM is enabled
      return;
    }
    PendingConfig pending = state.takePendingConfig();
    if (pending != null) {
      log.debug("Received cluster ID, reporting {} config", pending.type);
      Map<String, String> config =
          pending.clientConfig != null
              ? extractClientConfig(pending.clientConfig)
              : extractConsumerConfigFromMap(pending.rawConfig);
      AgentTracer.get()
          .getDataStreamsMonitoring()
          .reportKafkaConfig(pending.type, clusterId, pending.consumerGroup, config);
    }
    if (clusterId != null && !clusterId.isEmpty()) {
      reportPendingConsumerGroupMember(state, clusterId);
    }
  }

  static Map<String, String> extractClientConfig(AbstractConfig clientConfig) {
    try {
      return convertToStringMap(clientConfig.values());
    } catch (Exception e) {
      log.debug("Error extracting client configuration", e);
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

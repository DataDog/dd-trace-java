package datadog.trace.instrumentation.kafka_clients38;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.InstrumentationContext;
import java.util.List;
import net.bytebuddy.asm.Advice;
import org.apache.kafka.clients.Metadata;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerGroupMetadata;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.internals.ConsumerDelegate;
import org.apache.kafka.clients.consumer.internals.OffsetCommitCallbackInvoker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ConstructorAdvice {
  private static final Logger log = LoggerFactory.getLogger(ConstructorAdvice.class);

  // new - capturing OffsetCommitCallbackInvoker instead of the old ConsumerCoordinator
  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void captureGroup(
      @Advice.This ConsumerDelegate consumer,
      @Advice.Argument(0) ConsumerConfig consumerConfig,
      @Advice.FieldValue("offsetCommitCallbackInvoker")
          OffsetCommitCallbackInvoker offsetCommitCallbackInvoker,
      @Advice.FieldValue("metadata") Metadata metadata) {
    ConsumerGroupMetadata groupMetadata = consumer.groupMetadata();
    String consumerGroup = consumerConfig.getString(ConsumerConfig.GROUP_ID_CONFIG);
    String normalizedConsumerGroup =
        consumerGroup != null && !consumerGroup.isEmpty() ? consumerGroup : null;
    if (normalizedConsumerGroup == null) {
      if (groupMetadata != null) {
        normalizedConsumerGroup = groupMetadata.groupId();
      }
    }
    List<String> bootstrapServersList =
        consumerConfig.getList(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG);
    String bootstrapServers = null;
    if (bootstrapServersList != null && !bootstrapServersList.isEmpty()) {
      bootstrapServers = String.join(",", bootstrapServersList);
    }
    KafkaConsumerInfo kafkaConsumerInfo;
    if (Config.get().isDataStreamsEnabled()) {
      kafkaConsumerInfo =
          new KafkaConsumerInfo(normalizedConsumerGroup, metadata, bootstrapServers);
    } else {
      kafkaConsumerInfo = new KafkaConsumerInfo(normalizedConsumerGroup, bootstrapServers);
    }
    // new - searching context for ConsumerDelegate and OffsetCommitCallbackInvoker instead of
    // ConsumerCoordinator and KafkaConsumer
    if (kafkaConsumerInfo.getConsumerGroup().isPresent()
        || kafkaConsumerInfo.getmetadata().isPresent()) {
      InstrumentationContext.get(ConsumerDelegate.class, KafkaConsumerInfo.class)
          .put(consumer, kafkaConsumerInfo);
    }
    if (offsetCommitCallbackInvoker != null) {
      InstrumentationContext.get(OffsetCommitCallbackInvoker.class, KafkaConsumerInfo.class)
          .put(offsetCommitCallbackInvoker, kafkaConsumerInfo);
    }

    // Log consumer configuration
    logConsumerConfiguration(consumerConfig, normalizedConsumerGroup);
  }

  private static void logConsumerConfiguration(
      ConsumerConfig consumerConfig, String consumerGroup) {
    try {
      log.info("Kafka Consumer started - Group: {}", consumerGroup);
      log.info("Consumer Configuration (all properties):");
      
      // Get all configuration values
      java.util.Map<String, ?> allConfigs = consumerConfig.values();
      
      // Sort by key for consistent output
      allConfigs.entrySet().stream()
          .sorted(java.util.Map.Entry.comparingByKey())
          .forEach(entry -> {
            log.info("  {}: {}", entry.getKey(), entry.getValue());
          });
      
      // TODO: Add data capture logic here
    } catch (Exception e) {
      log.debug("Error logging consumer configuration", e);
    }
  }

  public static void muzzleCheck(ConsumerRecord record) {
    // KafkaConsumerInstrumentation only applies for kafka versions with headers
    // Make an explicit call so KafkaConsumerGroupInstrumentation does the same
    record.headers();
  }
}

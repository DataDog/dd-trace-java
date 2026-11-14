package datadog.trace.instrumentation.kafka_clients38;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.traceConfig;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.instrumentation.kafka_common.KafkaConfigHelper;
import datadog.trace.instrumentation.kafka_common.MetadataState;
import java.util.List;
import net.bytebuddy.asm.Advice;
import org.apache.kafka.clients.Metadata;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerGroupMetadata;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.internals.ConsumerDelegate;
import org.apache.kafka.clients.consumer.internals.OffsetCommitCallbackInvoker;

public class ConstructorAdvice {
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
    // Group-less consumers built while DSM is off aren't stored, so they never get a cluster ID
    KafkaConsumerInfo kafkaConsumerInfo =
        new KafkaConsumerInfo(normalizedConsumerGroup, metadata, bootstrapServers, true);
    // new - searching context for ConsumerDelegate and OffsetCommitCallbackInvoker instead of
    // ConsumerCoordinator and KafkaConsumer
    if (kafkaConsumerInfo.getConsumerGroup().isPresent()
        // Startup config covers consumers built before the tracer is installed
        || (kafkaConsumerInfo.getmetadata().isPresent()
            && (Config.get().isDataStreamsEnabled() || traceConfig().isDataStreamsEnabled()))) {
      InstrumentationContext.get(ConsumerDelegate.class, KafkaConsumerInfo.class)
          .put(consumer, kafkaConsumerInfo);
    }
    if (offsetCommitCallbackInvoker != null) {
      InstrumentationContext.get(OffsetCommitCallbackInvoker.class, KafkaConsumerInfo.class)
          .put(offsetCommitCallbackInvoker, kafkaConsumerInfo);
    }

    // Captured even with DSM off; only reported while DSM is enabled
    MetadataState state =
        InstrumentationContext.get(Metadata.class, MetadataState.class)
            .getOrCreate(metadata, MetadataState::new);
    KafkaConfigHelper.storePendingConsumerConfig(state, normalizedConsumerGroup, consumerConfig);
  }

  public static void muzzleCheck(ConsumerRecord record) {
    // KafkaConsumerInstrumentation only applies for kafka versions with headers
    // Make an explicit call so KafkaConsumerGroupInstrumentation does the same
    record.headers();
  }
}

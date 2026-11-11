package datadog.trace.instrumentation.kafka_clients38;

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
    String consumerGroup = consumerConfig.getString(ConsumerConfig.GROUP_ID_CONFIG);
    String normalizedConsumerGroup =
        consumerGroup != null && !consumerGroup.isEmpty() ? consumerGroup : null;
    if (normalizedConsumerGroup == null) {
      try {
        // groupMetadata() throws InvalidGroupIdException for a consumer without a group, which
        // would otherwise drop the bootstrap servers and cluster ID too
        ConsumerGroupMetadata groupMetadata = consumer.groupMetadata();
        if (groupMetadata != null) {
          normalizedConsumerGroup = groupMetadata.groupId();
        }
      } catch (RuntimeException ignored) {
        // no consumer group
      }
    }
    List<String> bootstrapServersList =
        consumerConfig.getList(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG);
    String bootstrapServers = null;
    if (bootstrapServersList != null && !bootstrapServersList.isEmpty()) {
      bootstrapServers = String.join(",", bootstrapServersList);
    }
    // Keep the metadata even without Data Streams: MetadataInstrumentation records the cluster
    // ID on it, which consumer spans are tagged with.
    KafkaConsumerInfo kafkaConsumerInfo =
        new KafkaConsumerInfo(normalizedConsumerGroup, metadata, bootstrapServers);
    // new - searching context for ConsumerDelegate and OffsetCommitCallbackInvoker instead of
    // ConsumerCoordinator and KafkaConsumer
    if (kafkaConsumerInfo.getConsumerGroup().isPresent()
        || kafkaConsumerInfo.getmetadata().isPresent()
        || kafkaConsumerInfo.getBootstrapServers().isPresent()) {
      InstrumentationContext.get(ConsumerDelegate.class, KafkaConsumerInfo.class)
          .put(consumer, kafkaConsumerInfo);
    }
    if (offsetCommitCallbackInvoker != null) {
      InstrumentationContext.get(OffsetCommitCallbackInvoker.class, KafkaConsumerInfo.class)
          .put(offsetCommitCallbackInvoker, kafkaConsumerInfo);
    }

    if (Config.get().isDataStreamsEnabled()) {
      MetadataState state =
          InstrumentationContext.get(Metadata.class, MetadataState.class)
              .getOrCreate(metadata, MetadataState::new);
      KafkaConfigHelper.storePendingConsumerConfig(
          state, normalizedConsumerGroup, KafkaConfigHelper.extractConsumerConfig(consumerConfig));
    }
  }

  public static void muzzleCheck(ConsumerRecord record) {
    // KafkaConsumerInstrumentation only applies for kafka versions with headers
    // Make an explicit call so KafkaConsumerGroupInstrumentation does the same
    record.headers();
  }
}

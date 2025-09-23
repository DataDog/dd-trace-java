package datadog.trace.instrumentation.kafka_clients38;

import datadog.trace.api.datastreams.DataStreamsTags;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import org.apache.kafka.clients.Metadata;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.internals.ConsumerCoordinator;
import org.apache.kafka.clients.consumer.internals.RequestFuture;
import org.apache.kafka.common.TopicPartition;

public class ConsumerCoordinatorAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void trackCommitOffset(
      @Advice.This ConsumerCoordinator coordinator,
      @Advice.Return RequestFuture<Void> requestFuture,
      @Advice.Argument(0) final Map<TopicPartition, OffsetAndMetadata> offsets) {
    if (requestFuture == null || requestFuture.failed()) {
      return;
    }
    if (offsets == null) {
      return;
    }
    KafkaConsumerInfo kafkaConsumerInfo =
        InstrumentationContext.get(ConsumerCoordinator.class, KafkaConsumerInfo.class)
            .get(coordinator);

    if (kafkaConsumerInfo == null) {
      return;
    }

    String consumerGroup = kafkaConsumerInfo.getConsumerGroup().get();
    Metadata consumerMetadata = kafkaConsumerInfo.getmetadata().get();
    String clusterId = null;
    if (consumerMetadata != null) {
      clusterId = InstrumentationContext.get(Metadata.class, String.class).get(consumerMetadata);
    }

    for (Map.Entry<TopicPartition, OffsetAndMetadata> entry : offsets.entrySet()) {
      if (consumerGroup == null) {
        consumerGroup = "";
      }
      if (entry.getKey() == null || entry.getValue() == null) {
        continue;
      }
      DataStreamsTags tags =
          DataStreamsTags.createWithPartition(
              "kafka_commit",
              entry.getKey().topic(),
              String.valueOf(entry.getKey().partition()),
              clusterId,
              consumerGroup);
      AgentTracer.get().getDataStreamsMonitoring().trackBacklog(tags, entry.getValue().offset());
    }
  }

  public static void muzzleCheck(ConsumerRecord record) {
    // KafkaConsumerInstrumentation only applies for kafka versions with headers
    // Make an explicit call so ConsumerCoordinatorInstrumentation does the same
    record.headers();
  }
}

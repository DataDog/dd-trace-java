package datadog.trace.instrumentation.kafka_clients38;

import static datadog.trace.core.datastreams.TagsProcessor.CONSUMER_GROUP_TAG;
import static datadog.trace.core.datastreams.TagsProcessor.KAFKA_CLUSTER_ID_TAG;
import static datadog.trace.core.datastreams.TagsProcessor.PARTITION_TAG;
import static datadog.trace.core.datastreams.TagsProcessor.TOPIC_TAG;
import static datadog.trace.core.datastreams.TagsProcessor.TYPE_TAG;

import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.LinkedHashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import org.apache.kafka.clients.Metadata;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.internals.ConsumerCoordinator;
import org.apache.kafka.clients.consumer.internals.OffsetCommitCallbackInvoker;
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
      LinkedHashMap<String, String> sortedTags = new LinkedHashMap<>();
      sortedTags.put(CONSUMER_GROUP_TAG, consumerGroup);
      if (clusterId != null) {
        sortedTags.put(KAFKA_CLUSTER_ID_TAG, clusterId);
      }
      sortedTags.put(PARTITION_TAG, String.valueOf(entry.getKey().partition()));
      sortedTags.put(TOPIC_TAG, entry.getKey().topic());
      sortedTags.put(TYPE_TAG, "kafka_commit");
      AgentTracer.get()
          .getDataStreamsMonitoring()
          .trackBacklog(sortedTags, entry.getValue().offset());
    }
  }

  public static void muzzleCheck(OffsetCommitCallbackInvoker invoker) {
    // Only applies for kafka versions with OffsetCommitCallbackInvoker
    invoker.executeCallbacks();
  }
}

package datadog.trace.instrumentation.kafka_clients38;

import datadog.trace.api.datastreams.DataStreamsTags;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.instrumentation.kafka_common.MetadataState;
import java.util.Map;
import org.apache.kafka.clients.Metadata;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.OffsetCommitCallback;
import org.apache.kafka.common.TopicPartition;

public class DDOffsetCommitCallback implements OffsetCommitCallback {
  OffsetCommitCallback callback;
  KafkaConsumerInfo kafkaConsumerInfo;
  private final ContextStore<Metadata, MetadataState> metadataStore;

  public DDOffsetCommitCallback(
      OffsetCommitCallback callback,
      KafkaConsumerInfo kafkaConsumerInfo,
      ContextStore<Metadata, MetadataState> metadataStore) {
    this.callback = callback;
    this.kafkaConsumerInfo = kafkaConsumerInfo;
    this.metadataStore = metadataStore;
  }

  @Override
  public void onComplete(Map<TopicPartition, OffsetAndMetadata> map, Exception e) {
    if (callback != null) {
      callback.onComplete(map, e);
    }
    for (Map.Entry<TopicPartition, OffsetAndMetadata> entry : map.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        continue;
      }
      String consumerGroup = null;
      String clusterId = null;

      if (kafkaConsumerInfo != null) {
        consumerGroup = kafkaConsumerInfo.getConsumerGroup().orElse(null);
        Metadata consumerMetadata = kafkaConsumerInfo.getmetadata().orElse(null);
        if (consumerMetadata != null) {
          MetadataState metadataState = metadataStore.get(consumerMetadata);
          clusterId = metadataState != null ? metadataState.clusterId : null;
        }
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
}

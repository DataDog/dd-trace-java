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
import org.apache.kafka.clients.Metadata;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.OffsetCommitCallback;
import org.apache.kafka.common.TopicPartition;

public class DDOffsetCommitCallback implements OffsetCommitCallback {
  OffsetCommitCallback callback;
  KafkaConsumerInfo kafkaConsumerInfo;

  public DDOffsetCommitCallback(
      OffsetCommitCallback callback, KafkaConsumerInfo kafkaConsumerInfo) {
    this.callback = callback;
    this.kafkaConsumerInfo = kafkaConsumerInfo;
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
      LinkedHashMap<String, String> sortedTags = new LinkedHashMap<>();
      if (kafkaConsumerInfo != null) {
        String consumerGroup = kafkaConsumerInfo.getConsumerGroup().get();
        Metadata consumerMetadata = kafkaConsumerInfo.getmetadata().get();
        String clusterId = null;
        if (consumerMetadata != null) {
          clusterId =
              InstrumentationContext.get(Metadata.class, String.class).get(consumerMetadata);
        }
        sortedTags.put(CONSUMER_GROUP_TAG, consumerGroup);
        if (clusterId != null) {
          sortedTags.put(KAFKA_CLUSTER_ID_TAG, clusterId);
        }
      }

      sortedTags.put(PARTITION_TAG, String.valueOf(entry.getKey().partition()));
      sortedTags.put(TOPIC_TAG, entry.getKey().topic());
      sortedTags.put(TYPE_TAG, "kafka_commit");
      AgentTracer.get()
          .getDataStreamsMonitoring()
          .trackBacklog(sortedTags, entry.getValue().offset());
    }
  }
}

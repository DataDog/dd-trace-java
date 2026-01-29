package datadog.trace.instrumentation.kafka_clients38;

import datadog.trace.api.datastreams.DataStreamsTags;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
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
      String consumerGroup = null;
      String clusterId = null;

      if (kafkaConsumerInfo != null) {
        consumerGroup = kafkaConsumerInfo.getConsumerGroup().orElse(null);
        Metadata consumerMetadata = kafkaConsumerInfo.getmetadata().orElse(null);
        if (consumerMetadata != null) {
          clusterId =
              InstrumentationContext.get(Metadata.class, String.class).get(consumerMetadata);
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

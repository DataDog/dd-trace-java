package datadog.trace.instrumentation.kafka_clients38;

import datadog.trace.api.datastreams.AgentDataStreamsMonitoring;
import datadog.trace.api.datastreams.DataStreamsTags;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.internals.RequestFutureListener;
import org.apache.kafka.common.TopicPartition;

public final class OffsetCommitBacklogListener implements RequestFutureListener<Void> {
  private final Map<TopicPartition, OffsetAndMetadata> offsets;
  private final String clusterId;
  private final String consumerGroup;
  private final AgentDataStreamsMonitoring monitoring;
  private final String serviceName;

  public OffsetCommitBacklogListener(
      Map<TopicPartition, OffsetAndMetadata> offsets, String clusterId, String consumerGroup) {
    this.offsets = new HashMap<>(offsets);
    this.clusterId = clusterId;
    this.consumerGroup = consumerGroup;
    this.monitoring = AgentTracer.get().getDataStreamsMonitoring();
    this.serviceName = monitoring.getThreadServiceName();
  }

  @Override
  public void onSuccess(Void result) {
    try {
      String completionServiceName = monitoring.getThreadServiceName();
      boolean switchService = !Objects.equals(serviceName, completionServiceName);
      // A heartbeat thread can complete a request submitted by a Kafka Connect task.
      try {
        if (switchService) {
          monitoring.setThreadServiceName(serviceName);
        }
        trackBacklogs();
      } finally {
        if (switchService) {
          monitoring.setThreadServiceName(completionServiceName);
        }
      }
    } catch (Throwable ignored) {
      // Kafka propagates listener failures into request completion.
    }
  }

  private void trackBacklogs() {
    for (Map.Entry<TopicPartition, OffsetAndMetadata> entry : offsets.entrySet()) {
      TopicPartition partition = entry.getKey();
      OffsetAndMetadata offset = entry.getValue();
      if (partition == null || offset == null) {
        continue;
      }
      DataStreamsTags tags =
          DataStreamsTags.createWithPartition(
              "kafka_commit",
              partition.topic(),
              String.valueOf(partition.partition()),
              clusterId,
              consumerGroup);
      monitoring.trackBacklog(tags, offset.offset());
    }
  }

  @Override
  public void onFailure(RuntimeException exception) {}
}

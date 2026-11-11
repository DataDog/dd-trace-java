package datadog.trace.instrumentation.kafka_clients38;

import static datadog.trace.api.KnownTags.MESSAGING_KAFKA_CLUSTER_ID;
import static datadog.trace.api.KnownTags.OFFSET;
import static datadog.trace.api.KnownTags.PARTITION;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.traceConfig;
import static datadog.trace.instrumentation.kafka_clients38.KafkaDecorator.PRODUCER_DECORATE;

import datadog.context.ContextScope;
import datadog.trace.api.datastreams.DataStreamsTags;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.instrumentation.kafka_common.MetadataState;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.RecordMetadata;

public class KafkaProducerCallback implements Callback {
  private final Callback callback;
  private final AgentSpan parent;
  private final AgentSpan span;
  private final MetadataState metadataState;
  private String clusterId;

  public KafkaProducerCallback(
      final Callback callback,
      final AgentSpan parent,
      final AgentSpan span,
      final String clusterId,
      final MetadataState metadataState) {
    this.callback = callback;
    this.parent = parent;
    this.span = span;
    this.clusterId = clusterId;
    this.metadataState = metadataState;
  }

  @Override
  public void onCompletion(final RecordMetadata metadata, final Exception exception) {
    if (metadata != null) {
      span.setTag(PARTITION, metadata.partition());
      span.setTag(OFFSET, metadata.offset());
    }
    if (clusterId == null && metadataState != null) {
      // A send that starts before the producer's first metadata update has no cluster ID yet; the
      // update it waited for has recorded it by now.
      clusterId = metadataState.clusterId;
      if (clusterId != null) {
        span.setTag(MESSAGING_KAFKA_CLUSTER_ID, clusterId);
      }
    }
    PRODUCER_DECORATE.onError(span, exception);
    PRODUCER_DECORATE.beforeFinish(span);
    span.finish();
    if (callback != null) {
      if (parent != null) {
        try (final ContextScope scope = activateSpan(parent)) {
          callback.onCompletion(metadata, exception);
        }
      } else {
        callback.onCompletion(metadata, exception);
      }
    }
    // trackBacklog is a no-op with DSM off, so skip building its tags.
    if (metadata == null
        || exception != null
        || metadata.partition() < 0
        || !traceConfig().isDataStreamsEnabled()) {
      return;
    }
    DataStreamsTags tags =
        DataStreamsTags.createWithPartition(
            "kafka_produce",
            metadata.topic(),
            String.valueOf(metadata.partition()),
            clusterId,
            null);
    AgentTracer.get().getDataStreamsMonitoring().trackBacklog(tags, metadata.offset());
  }
}

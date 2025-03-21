package datadog.trace.instrumentation.kafka_clients;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.core.datastreams.TagsProcessor.KAFKA_CLUSTER_ID_TAG;
import static datadog.trace.core.datastreams.TagsProcessor.PARTITION_TAG;
import static datadog.trace.core.datastreams.TagsProcessor.TOPIC_TAG;
import static datadog.trace.core.datastreams.TagsProcessor.TYPE_TAG;
import static datadog.trace.instrumentation.kafka_clients.KafkaDecorator.PRODUCER_DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.LinkedHashMap;
import javax.annotation.Nullable;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.RecordMetadata;

public class KafkaProducerCallback implements Callback {
  private final Callback callback;
  private final AgentSpan parent;
  private final AgentSpan span;
  @Nullable private final String clusterId;

  public KafkaProducerCallback(
      final Callback callback,
      final AgentSpan parent,
      final AgentSpan span,
      @Nullable final String clusterId) {
    this.callback = callback;
    this.parent = parent;
    this.span = span;
    this.clusterId = clusterId;
  }

  @Override
  public void onCompletion(final RecordMetadata metadata, final Exception exception) {
    PRODUCER_DECORATE.onError(span, exception);
    PRODUCER_DECORATE.beforeFinish(span);
    span.finish();
    if (callback != null) {
      if (parent != null) {
        try (final AgentScope scope = activateSpan(parent)) {
          callback.onCompletion(metadata, exception);
        }
      } else {
        callback.onCompletion(metadata, exception);
      }
    }
    if (metadata == null) {
      return;
    }
    LinkedHashMap<String, String> sortedTags = new LinkedHashMap<>();
    if (clusterId != null) {
      sortedTags.put(KAFKA_CLUSTER_ID_TAG, clusterId);
    }
    sortedTags.put(PARTITION_TAG, String.valueOf(metadata.partition()));
    sortedTags.put(TOPIC_TAG, metadata.topic());
    sortedTags.put(TYPE_TAG, "kafka_produce");
    AgentTracer.get().getDataStreamsMonitoring().trackBacklog(sortedTags, metadata.offset());
  }
}

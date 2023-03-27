package datadog.trace.instrumentation.kafka_clients;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.core.datastreams.TagsProcessor.PARTITION_TAG;
import static datadog.trace.core.datastreams.TagsProcessor.TOPIC_TAG;
import static datadog.trace.core.datastreams.TagsProcessor.TYPE_TAG;
import static datadog.trace.instrumentation.kafka_clients.KafkaDecorator.PRODUCER_DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.LinkedHashMap;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.RecordMetadata;

public class KafkaProducerCallback implements Callback {
  private final Callback callback;
  private final AgentSpan parent;
  private final AgentSpan span;

  public KafkaProducerCallback(
      final Callback callback, final AgentSpan parent, final AgentSpan span) {
    this.callback = callback;
    this.parent = parent;
    this.span = span;
  }

  @Override
  public void onCompletion(final RecordMetadata metadata, final Exception exception) {
    PRODUCER_DECORATE.onError(span, exception);
    PRODUCER_DECORATE.beforeFinish(span);
    span.finish();
    if (callback != null) {
      if (parent != null) {
        try (final AgentScope scope = activateSpan(parent)) {
          scope.setAsyncPropagation(true);
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
    sortedTags.put(PARTITION_TAG, String.valueOf(metadata.partition()));
    sortedTags.put(TOPIC_TAG, metadata.topic());
    sortedTags.put(TYPE_TAG, "kafka_produce");
    AgentTracer.get().getDataStreamsMonitoring().trackBacklog(sortedTags, metadata.offset());
  }
}

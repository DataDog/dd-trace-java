package datadog.trace.instrumentation.kafka_clients;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.instrumentation.kafka_clients.KafkaDecorator.PRODUCER_DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
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
    // this is too late, this should be emitted before any work is done,
    // but it's also impossible to do that because of the way Kafka's
    // batching works.
    span.finishThreadMigration();
    PRODUCER_DECORATE.onError(span, exception);
    PRODUCER_DECORATE.beforeFinish(span);
    span.finish();
    if (callback != null) {
      if (parent != null) {
        try (final AgentScope scope = activateSpan(parent)) {
          scope.setAsyncPropagation(true);
          parent.finishThreadMigration();
          callback.onCompletion(metadata, exception);
        }
      } else {
        callback.onCompletion(metadata, exception);
      }
    }
  }
}

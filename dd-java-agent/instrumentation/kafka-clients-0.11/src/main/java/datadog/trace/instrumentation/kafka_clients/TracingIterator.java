package datadog.trace.instrumentation.kafka_clients;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.kafka_clients.TextMapExtractAdapter.GETTER;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan.Context;
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags;
import java.util.Iterator;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;

@Slf4j
public class TracingIterator implements Iterator<ConsumerRecord> {
  private final Iterator<ConsumerRecord> delegateIterator;
  private final String operationName;
  private final KafkaDecorator decorator;

  /**
   * Note: this may potentially create problems if this iterator is used from different threads. But
   * at the moment we cannot do much about this.
   */
  private AgentScope currentScope;

  public TracingIterator(
      final Iterator<ConsumerRecord> delegateIterator,
      final String operationName,
      final KafkaDecorator decorator) {
    this.delegateIterator = delegateIterator;
    this.operationName = operationName;
    this.decorator = decorator;
  }

  @Override
  public boolean hasNext() {
    if (currentScope != null) {
      finish();
    }
    return delegateIterator.hasNext();
  }

  @Override
  public ConsumerRecord next() {
    if (currentScope != null) {
      // in case they didn't call hasNext()...
      finish();
    }

    final ConsumerRecord next = delegateIterator.next();

    try {
      if (next != null) {
        final Context spanContext = propagate().extract(next.headers(), GETTER);
        final AgentSpan span = startSpan(operationName, spanContext);
        // tombstone checking logic here because it can only be inferred
        // from the record itself
        if (next.value() == null && !next.headers().iterator().hasNext()) {
          span.setTag(InstrumentationTags.TOMBSTONE, true);
        }
        decorator.afterStart(span);
        decorator.onConsume(span, next);
        currentScope = activateSpan(span);
        currentScope.setAsyncPropagation(true);
      }
    } catch (final Exception e) {
      log.debug("Error during decoration", e);
    }
    return next;
  }

  private void finish() {
    currentScope.close();
    decorator.finishConsumerSpan(currentScope.span());
    currentScope = null;
  }

  @Override
  public void remove() {
    delegateIterator.remove();
  }
}

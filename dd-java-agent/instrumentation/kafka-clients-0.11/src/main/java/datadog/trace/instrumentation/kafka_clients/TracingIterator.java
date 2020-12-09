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
public class TracingIterator implements Iterator<ConsumerRecord<?, ?>> {
  private final Iterator<ConsumerRecord<?, ?>> delegateIterator;
  private final CharSequence operationName;
  private final KafkaDecorator decorator;

  /**
   * Note: this may potentially create problems if this iterator is used from different threads. But
   * at the moment we cannot do much about this.
   */
  private AgentScope currentScope;

  public TracingIterator(
      final Iterator<ConsumerRecord<?, ?>> delegateIterator,
      final CharSequence operationName,
      final KafkaDecorator decorator) {
    this.delegateIterator = delegateIterator;
    this.operationName = operationName;
    this.decorator = decorator;
  }

  @Override
  public boolean hasNext() {
    final boolean delegateHasNext = delegateIterator.hasNext();
    if (!delegateHasNext) {
      // close scope only for last iteration, because next() most probably not going to be called.
      // If it's not last iteration we expect scope will be closed inside next()
      maybeCloseCurrentScope();
    }
    return delegateHasNext;
  }

  @Override
  public ConsumerRecord<?, ?> next() {
    maybeCloseCurrentScope();
    final ConsumerRecord<?, ?> next = delegateIterator.next();
    decorate(next);
    return next;
  }

  protected void decorate(ConsumerRecord<?, ?> val) {
    try {
      if (val != null) {
        final Context spanContext = propagate().extract(val.headers(), GETTER);
        final AgentSpan span = startSpan(operationName, spanContext);
        if (val.value() == null) {
          span.setTag(InstrumentationTags.TOMBSTONE, true);
        }
        decorator.afterStart(span);
        decorator.onConsume(span, val);
        currentScope = activateSpan(span);
        currentScope.setAsyncPropagation(true);
      }
    } catch (final Exception e) {
      log.debug("Error during decoration", e);
    }
  }

  protected void maybeCloseCurrentScope() {
    if (currentScope != null) {
      currentScope.close();
      decorator.finishConsumerSpan(currentScope.span());
      currentScope = null;
    }
  }

  @Override
  public void remove() {
    delegateIterator.remove();
  }
}

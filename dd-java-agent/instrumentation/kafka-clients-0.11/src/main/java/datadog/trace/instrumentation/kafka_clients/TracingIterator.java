package datadog.trace.instrumentation.kafka_clients;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.closePrevious;

import java.util.Iterator;
import org.apache.kafka.clients.consumer.ConsumerRecord;

public class TracingIterator implements Iterator<ConsumerRecord<?, ?>> {
  private final Iterator<ConsumerRecord<?, ?>> delegateIterator;
  private final CharSequence operationName;
  private final KafkaDecorator decorator;
  private final String group;

  public TracingIterator(
      final Iterator<ConsumerRecord<?, ?>> delegateIterator,
      final CharSequence operationName,
      final KafkaDecorator decorator,
      String group) {
    this.delegateIterator = delegateIterator;
    this.operationName = operationName;
    this.decorator = decorator;
    this.group = group;
  }

  @Override
  public boolean hasNext() {
    boolean moreRecords = delegateIterator.hasNext();
    if (!moreRecords) {
      // no more records, use this as a signal to close the last iteration scope
      closePrevious(true);
    }
    return moreRecords;
  }

  @Override
  public ConsumerRecord<?, ?> next() {
    final ConsumerRecord<?, ?> next = delegateIterator.next();
    startNewRecordSpan(next);
    return next;
  }

  protected void startNewRecordSpan(ConsumerRecord<?, ?> val) {
    IteratorUtils.startNewRecordSpan(val, this.operationName, this.group, this.decorator);
  }

  @Override
  public void remove() {
    delegateIterator.remove();
  }
}

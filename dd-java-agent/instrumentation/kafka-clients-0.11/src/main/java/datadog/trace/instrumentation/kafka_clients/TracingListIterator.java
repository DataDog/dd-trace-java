package datadog.trace.instrumentation.kafka_clients;

import java.util.ListIterator;
import org.apache.kafka.clients.consumer.ConsumerRecord;

public class TracingListIterator extends TracingIterator
    implements ListIterator<ConsumerRecord<?, ?>> {

  private final ListIterator<ConsumerRecord<?, ?>> delegateIterator;

  public TracingListIterator(
      ListIterator<ConsumerRecord<?, ?>> delegateIterator,
      CharSequence operationName,
      KafkaDecorator decorator) {
    super(delegateIterator, operationName, decorator);
    this.delegateIterator = delegateIterator;
  }

  @Override
  public boolean hasPrevious() {
    final boolean delegateHasPrevious = delegateIterator.hasPrevious();
    if (!delegateHasPrevious) {
      // close scope only for last iteration, because previous() most probably not going to be
      // called.
      // If it's not last iteration we expect scope will be closed inside previous()
      maybeCloseCurrentScope();
    }
    return delegateHasPrevious;
  }

  @Override
  public ConsumerRecord<?, ?> previous() {
    maybeCloseCurrentScope();
    final ConsumerRecord<?, ?> prev = delegateIterator.previous();
    decorate(prev);
    return prev;
  }

  @Override
  public int nextIndex() {
    return delegateIterator.nextIndex();
  }

  @Override
  public int previousIndex() {
    return delegateIterator.previousIndex();
  }

  /*
   * org.apache.kafka.clients.consumer.ConsumerRecords::records(TopicPartition) always returns
   * UnmodifiableList. Modifiable operations will lead to exception
   */

  @Override
  public void set(ConsumerRecord<?, ?> consumerRecord) {
    delegateIterator.set(consumerRecord);
  }

  @Override
  public void add(ConsumerRecord<?, ?> consumerRecord) {
    delegateIterator.add(consumerRecord);
  }
}

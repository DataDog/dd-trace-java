package datadog.trace.instrumentation.kafka_clients38;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.closePrevious;

import java.util.ListIterator;
import org.apache.kafka.clients.consumer.ConsumerRecord;

public class TracingListIterator extends TracingIterator
    implements ListIterator<ConsumerRecord<?, ?>> {

  private final ListIterator<ConsumerRecord<?, ?>> delegateIterator;

  public TracingListIterator(
      ListIterator<ConsumerRecord<?, ?>> delegateIterator,
      CharSequence operationName,
      KafkaDecorator decorator,
      String group,
      String clusterId,
      String bootstrapServers) {
    super(delegateIterator, operationName, decorator, group, clusterId, bootstrapServers);
    this.delegateIterator = delegateIterator;
  }

  @Override
  public boolean hasPrevious() {
    boolean moreRecords = delegateIterator.hasPrevious();
    if (!moreRecords) {
      // no more records, use this as a signal to close the last iteration scope
      closePrevious(true);
    }
    return moreRecords;
  }

  @Override
  public ConsumerRecord<?, ?> previous() {
    final ConsumerRecord<?, ?> prev = delegateIterator.previous();
    startNewRecordSpan(prev);
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

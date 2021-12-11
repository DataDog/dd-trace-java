package datadog.trace.instrumentation.kafka_clients;

import java.util.Iterator;
import org.apache.kafka.clients.consumer.ConsumerRecord;

public final class TracingIterable
    implements Iterable<ConsumerRecord<?, ?>>, TracingIterableDelegator {
  private final Iterable<ConsumerRecord<?, ?>> delegate;
  private final CharSequence operationName;
  private final KafkaDecorator decorator;

  public TracingIterable(
      final Iterable<ConsumerRecord<?, ?>> delegate,
      final CharSequence operationName,
      final KafkaDecorator decorator) {
    this.delegate = delegate;
    this.operationName = operationName;
    this.decorator = decorator;
  }

  @Override
  public Iterator<ConsumerRecord<?, ?>> iterator() {
    // every iteration will add spans. Not only the very first one
    return new TracingIterator(delegate.iterator(), operationName, decorator);
  }

  @Override
  public Iterable<ConsumerRecord<?, ?>> getDelegate() {
    return delegate;
  }
}

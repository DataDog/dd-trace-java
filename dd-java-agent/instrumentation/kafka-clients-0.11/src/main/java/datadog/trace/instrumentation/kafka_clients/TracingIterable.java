package datadog.trace.instrumentation.kafka_clients;

import java.util.Iterator;
import org.apache.kafka.clients.consumer.ConsumerRecord;

public class TracingIterable implements Iterable<ConsumerRecord<?, ?>>, TracingIterableDelegator {
  private final Iterable<ConsumerRecord<?, ?>> delegate;
  private final CharSequence operationName;
  private final KafkaDecorator decorator;
  private final ConsumerContext context;

  public TracingIterable(
      final Iterable<ConsumerRecord<?, ?>> delegate,
      final CharSequence operationName,
      final KafkaDecorator decorator,
      ConsumerContext context) {
    this.delegate = delegate;
    this.operationName = operationName;
    this.decorator = decorator;
    this.context = context;
  }

  @Override
  public Iterator<ConsumerRecord<?, ?>> iterator() {
    // every iteration will add spans. Not only the very first one
    return new TracingIterator(delegate.iterator(), operationName, decorator, context);
  }

  @Override
  public Iterable<ConsumerRecord<?, ?>> getDelegate() {
    return delegate;
  }
}

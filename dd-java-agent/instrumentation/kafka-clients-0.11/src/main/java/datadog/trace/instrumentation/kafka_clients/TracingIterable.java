package datadog.trace.instrumentation.kafka_clients;

import java.util.Iterator;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kafka.clients.consumer.ConsumerRecord;

public class TracingIterable implements Iterable<ConsumerRecord<?, ?>> {
  private final Iterable<ConsumerRecord<?, ?>> delegate;
  private final CharSequence operationName;
  private final KafkaDecorator decorator;
  private final AtomicBoolean firstIterator = new AtomicBoolean(true);

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
}

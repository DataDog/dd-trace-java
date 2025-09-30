package datadog.trace.instrumentation.kafka_clients38;

import java.util.Iterator;
import org.apache.kafka.clients.consumer.ConsumerRecord;

public class TracingIterable implements Iterable<ConsumerRecord<?, ?>>, TracingIterableDelegator {
  private final Iterable<ConsumerRecord<?, ?>> delegate;
  private final CharSequence operationName;
  private final KafkaDecorator decorator;
  private final String group;
  private final String clusterId;
  private final String bootstrapServers;

  public TracingIterable(
      final Iterable<ConsumerRecord<?, ?>> delegate,
      final CharSequence operationName,
      final KafkaDecorator decorator,
      String group,
      String clusterId,
      String bootstrapServers) {
    this.delegate = delegate;
    this.operationName = operationName;
    this.decorator = decorator;
    this.group = group;
    this.clusterId = clusterId;
    this.bootstrapServers = bootstrapServers;
  }

  @Override
  public Iterator<ConsumerRecord<?, ?>> iterator() {
    // every iteration will add spans. Not only the very first one
    return new TracingIterator(
        delegate.iterator(), operationName, decorator, group, clusterId, bootstrapServers);
  }

  @Override
  public Iterable<ConsumerRecord<?, ?>> getDelegate() {
    return delegate;
  }
}

package datadog.trace.instrumentation.kafka_clients38;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import org.apache.kafka.clients.consumer.ConsumerRecord;

public class TracingList implements List<ConsumerRecord<?, ?>>, TracingIterableDelegator {

  private final List<ConsumerRecord<?, ?>> delegate;
  private final CharSequence operationName;
  private final KafkaDecorator decorator;
  private final String group;
  private final String clusterId;
  private final String bootstrapServers;

  public TracingList(
      final List<ConsumerRecord<?, ?>> delegate,
      final CharSequence operationName,
      final KafkaDecorator decorator,
      String group,
      String clusterId,
      String bootstrapServers) {
    this.operationName = operationName;
    this.decorator = decorator;
    this.delegate = delegate;
    this.group = group;
    this.clusterId = clusterId;
    this.bootstrapServers = bootstrapServers;
  }

  @Override
  public int size() {
    return delegate.size();
  }

  @Override
  public boolean isEmpty() {
    return delegate.isEmpty();
  }

  @Override
  public boolean contains(final Object o) {
    return delegate.contains(o);
  }

  @Override
  public Iterator<ConsumerRecord<?, ?>> iterator() {
    return listIterator(0);
  }

  @Override
  public Object[] toArray() {
    return delegate.toArray();
  }

  @Override
  public <T> T[] toArray(final T[] a) {
    return delegate.toArray(a);
  }

  @Override
  public boolean add(final ConsumerRecord consumerRecord) {
    return delegate.add(consumerRecord);
  }

  @Override
  public boolean remove(final Object o) {
    return delegate.remove(o);
  }

  @Override
  public boolean containsAll(final Collection<?> c) {
    return delegate.containsAll(c);
  }

  @Override
  public boolean addAll(final Collection<? extends ConsumerRecord<?, ?>> c) {
    return delegate.addAll(c);
  }

  @Override
  public boolean addAll(final int index, final Collection<? extends ConsumerRecord<?, ?>> c) {
    return delegate.addAll(index, c);
  }

  @Override
  public boolean removeAll(final Collection<?> c) {
    return delegate.removeAll(c);
  }

  @Override
  public boolean retainAll(final Collection<?> c) {
    return delegate.retainAll(c);
  }

  @Override
  public void clear() {
    delegate.clear();
  }

  @Override
  public ConsumerRecord get(final int index) {
    // TODO: should this be instrumented as well?
    return delegate.get(index);
  }

  @Override
  public ConsumerRecord set(final int index, final ConsumerRecord element) {
    return delegate.set(index, element);
  }

  @Override
  public void add(final int index, final ConsumerRecord element) {
    delegate.add(index, element);
  }

  @Override
  public ConsumerRecord remove(final int index) {
    return delegate.remove(index);
  }

  @Override
  public int indexOf(final Object o) {
    return delegate.indexOf(o);
  }

  @Override
  public int lastIndexOf(final Object o) {
    return delegate.lastIndexOf(o);
  }

  @Override
  public ListIterator<ConsumerRecord<?, ?>> listIterator() {
    return listIterator(0);
  }

  @Override
  public ListIterator<ConsumerRecord<?, ?>> listIterator(final int index) {
    // every iteration will add spans. Not only the very first one
    return new TracingListIterator(
        delegate.listIterator(index), operationName, decorator, group, clusterId, bootstrapServers);
  }

  @Override
  public List<ConsumerRecord<?, ?>> subList(final int fromIndex, final int toIndex) {
    return new TracingList(
        delegate.subList(fromIndex, toIndex),
        operationName,
        decorator,
        group,
        clusterId,
        bootstrapServers);
  }

  @Override
  public List<ConsumerRecord<?, ?>> getDelegate() {
    return delegate;
  }
}

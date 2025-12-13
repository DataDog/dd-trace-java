package datadog.common.queue;

import java.util.Collection;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;
import org.jctools.queues.MpscBlockingConsumerArrayQueue;

/**
 * Delegate wrapper around JCTools MpscBlockingConsumerArrayQueue implementing
 * MessagePassingBlockingQueue interface.
 *
 * @param <E> element type
 */
final class MpscBlockingConsumerArrayQueueDelegate<E> implements MessagePassingBlockingQueue<E> {
  private final MpscBlockingConsumerArrayQueue<E> delegate;

  /**
   * @param capacity queue capacity
   */
  MpscBlockingConsumerArrayQueueDelegate(int capacity) {
    this.delegate = new MpscBlockingConsumerArrayQueue<>(capacity);
  }

  // BlockingQueue methods

  @Override
  public void put(E e) throws InterruptedException {
    delegate.put(e);
  }

  @Override
  public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
    return delegate.offer(e, timeout, unit);
  }

  @Override
  public E take() throws InterruptedException {
    return delegate.take();
  }

  @Override
  public E poll(long timeout, TimeUnit unit) throws InterruptedException {
    return delegate.poll(timeout, unit);
  }

  @Override
  public int remainingCapacity() {
    return delegate.remainingCapacity();
  }

  @Override
  public int drainTo(Collection<? super E> c) {
    return delegate.drainTo(c);
  }

  @Override
  public int drainTo(Collection<? super E> c, int maxElements) {
    return delegate.drainTo(c, maxElements);
  }

  // Queue methods

  @Override
  public boolean add(E e) {
    return delegate.add(e);
  }

  @Override
  public boolean offer(E e) {
    return delegate.offer(e);
  }

  @Override
  public E remove() {
    return delegate.remove();
  }

  @Override
  public E poll() {
    return delegate.poll();
  }

  @Override
  public E element() {
    return delegate.element();
  }

  @Override
  public E peek() {
    return delegate.peek();
  }

  // Collection methods

  @Override
  public int size() {
    return delegate.size();
  }

  @Override
  public boolean isEmpty() {
    return delegate.isEmpty();
  }

  @Override
  public boolean contains(Object o) {
    return delegate.contains(o);
  }

  @Override
  public Iterator<E> iterator() {
    return delegate.iterator();
  }

  @Override
  public Object[] toArray() {
    return delegate.toArray();
  }

  @Override
  public <T> T[] toArray(T[] a) {
    return delegate.toArray(a);
  }

  @Override
  public boolean remove(Object o) {
    return delegate.remove(o);
  }

  @Override
  public boolean containsAll(Collection<?> c) {
    return delegate.containsAll(c);
  }

  @Override
  public boolean addAll(Collection<? extends E> c) {
    return delegate.addAll(c);
  }

  @Override
  public boolean removeAll(Collection<?> c) {
    return delegate.removeAll(c);
  }

  @Override
  public boolean retainAll(Collection<?> c) {
    return delegate.retainAll(c);
  }

  @Override
  public void clear() {
    delegate.clear();
  }

  // MessagePassingQueue methods

  @Override
  public int capacity() {
    return delegate.capacity();
  }

  @Override
  public boolean relaxedOffer(E e) {
    return delegate.relaxedOffer(e);
  }

  @Override
  public E relaxedPoll() {
    return delegate.relaxedPoll();
  }

  @Override
  public E relaxedPeek() {
    return delegate.relaxedPeek();
  }

  @Override
  public int drain(Consumer<E> consumer) {
    return delegate.drain(consumer);
  }

  @Override
  public int drain(Consumer<E> consumer, int limit) {
    return delegate.drain(consumer, limit);
  }

  @Override
  public void drain(Consumer<E> consumer, WaitStrategy wait, ExitCondition exit) {
    delegate.drain(consumer, wait, exit);
  }

  @Override
  public int fill(Supplier<E> supplier) {
    return delegate.fill(supplier);
  }

  @Override
  public int fill(Supplier<E> supplier, int limit) {
    return delegate.fill(supplier, limit);
  }

  @Override
  public void fill(Supplier<E> supplier, WaitStrategy wait, ExitCondition exit) {
    delegate.fill(supplier, wait, exit);
  }
}

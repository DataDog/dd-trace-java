package datadog.common.queue;

import java.util.AbstractQueue;
import java.util.Iterator;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import org.jctools.queues.MessagePassingQueue;

/**
 * A {@link NonBlockingQueue} implementation that wraps a {@link MessagePassingQueue} from the
 * JCTools library to provide a consistent, framework-independent interface.
 *
 * <p>This adapter bridges JCTools’ queue APIs with the {@link NonBlockingQueue} abstraction used by
 * this library. All operations are directly delegated to the underlying {@code MessagePassingQueue}
 *
 * @param <E> the type of elements held in this queue
 */
class JctoolsWrappedQueue<E> extends AbstractQueue<E> implements NonBlockingQueue<E> {
  private final MessagePassingQueue<E> delegate;

  public JctoolsWrappedQueue(@Nonnull MessagePassingQueue<E> delegate) {
    this.delegate = delegate;
  }

  @Override
  public int drain(Consumer<E> consumer) {
    return delegate.drain(consumer::accept);
  }

  @Override
  public int drain(Consumer<E> consumer, int limit) {
    return delegate.drain(consumer::accept, limit);
  }

  @Override
  public int fill(@Nonnull Supplier<? extends E> supplier, int limit) {
    return delegate.fill(supplier::get, limit);
  }

  @Override
  public int remainingCapacity() {
    return capacity() - size();
  }

  @Override
  public int capacity() {
    return delegate.capacity();
  }

  @Override
  public Iterator<E> iterator() {
    throw new UnsupportedOperationException();
  }

  @Override
  public int size() {
    return delegate.size();
  }

  @Override
  public boolean offer(E e) {
    return delegate.offer(e);
  }

  @Override
  public E poll() {
    return delegate.poll();
  }

  @Override
  public E peek() {
    return delegate.peek();
  }
}

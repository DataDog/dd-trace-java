package datadog.common.queue;

import static datadog.trace.util.BitUtils.nextPowerOfTwo;

import java.util.AbstractQueue;
import java.util.Iterator;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.annotation.Nonnull;

/**
 * Base class for non-blocking queuing operations.
 *
 * @param <E> the type of elements held by this queue
 */
abstract class BaseQueue<E> extends AbstractQueue<E> implements NonBlockingQueue<E> {
  /** The capacity of the queue (must be a power of two) */
  protected final int capacity;

  /** Mask for fast modulo operation (index = pos & mask) */
  protected final int mask;

  public BaseQueue(int capacity) {
    this.capacity = nextPowerOfTwo(capacity);
    this.mask = this.capacity - 1;
  }

  @Override
  public int drain(Consumer<E> consumer) {
    return drain(consumer, Integer.MAX_VALUE);
  }

  @Override
  public int drain(@Nonnull Consumer<E> consumer, int limit) {
    int count = 0;
    E e;
    while (count < limit && (e = poll()) != null) {
      consumer.accept(e);
      count++;
    }
    return count;
  }

  @Override
  public int fill(@Nonnull Supplier<? extends E> supplier, int limit) {
    if (limit <= 0) {
      return 0;
    }

    int added = 0;
    while (added < limit) {
      E e = supplier.get();
      if (e == null) {
        break; // stop if supplier exhausted
      }

      if (offer(e)) {
        added++;
      } else {
        break; // queue is full
      }
    }
    return added;
  }

  /**
   * Iterator is not supported.
   *
   * @throws UnsupportedOperationException always
   */
  @Override
  public Iterator<E> iterator() {
    throw new UnsupportedOperationException();
  }

  @Override
  public int remainingCapacity() {
    return capacity - size();
  }

  @Override
  public int capacity() {
    return capacity;
  }
}

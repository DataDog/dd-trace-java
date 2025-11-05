package datadog.trace.util.queue;

import static datadog.trace.util.BitUtils.nextPowerOfTwo;

import java.util.AbstractQueue;
import java.util.Iterator;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.annotation.Nonnull;

public abstract class BaseQueue<E> extends AbstractQueue<E> implements NonBlockingQueue<E> {
  /** The capacity of the queue (must be a power of two) */
  protected final int capacity;

  /** Mask for fast modulo operation (index = pos & mask) */
  protected final int mask;

  public BaseQueue(int capacity) {
    this.capacity = nextPowerOfTwo(capacity);
    this.mask = this.capacity - 1;
  }

  /**
   * Drains all available elements from the queue to a consumer.
   *
   * <p>This is efficient since it avoids repeated size() checks and returns immediately when empty.
   *
   * @param consumer a consumer to accept elements
   * @return number of elements drained
   */
  @Override
  public int drain(Consumer<E> consumer) {
    return drain(consumer, Integer.MAX_VALUE);
  }

  /**
   * Drains up to {@code limit} elements from the queue to a consumer.
   *
   * <p>This method is useful for batch processing.
   *
   * <p>Each element is removed atomically using poll() and passed to the consumer.
   *
   * @param consumer a consumer to accept elements
   * @param limit maximum number of elements to drain
   * @return number of elements drained
   */
  @Override
  public int drain(Consumer<E> consumer, int limit) {
    int count = 0;
    E e;
    while (count < limit && (e = poll()) != null) {
      consumer.accept(e);
      count++;
    }
    return count;
  }

  /**
   * Fills the queue with elements provided by the supplier until either: - the queue is full, or -
   * the supplier runs out of elements (returns null)
   *
   * @param supplier a supplier of elements
   * @param limit maximum number of elements to attempt to insert
   * @return number of elements successfully enqueued
   */
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

  /**
   * Returns the remaining capacity.
   *
   * @return number of additional elements this queue can accept
   */
  @Override
  public int remainingCapacity() {
    return capacity - size();
  }

  /**
   * Returns the maximum queue capacity.
   *
   * @return number of total elements this queue can accept
   */
  @Override
  public int capacity() {
    return capacity;
  }
}

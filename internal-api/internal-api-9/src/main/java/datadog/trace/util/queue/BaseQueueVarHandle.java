package datadog.trace.util.queue;

import java.util.Collection;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.annotation.Nonnull;

public abstract class BaseQueueVarHandle<E> extends BaseQueue<E> {
  public BaseQueueVarHandle(int capacity) {
    super(capacity);
  }

  /**
   * Timed offer with progressive backoff.
   *
   * <p>Tries to insert an element into the queue within the given timeout. Uses a spin → yield →
   * park backoff strategy to reduce CPU usage under contention.
   *
   * @param e the element to insert
   * @param timeout maximum time to wait
   * @param unit time unit of timeout
   * @return {@code true} if inserted, {@code false} if timeout expires
   * @throws InterruptedException if interrupted while waiting
   */
  public boolean offer(E e, long timeout, @Nonnull TimeUnit unit) throws InterruptedException {
    if (e == null) {
      throw new NullPointerException();
    }
    final long deadline = System.nanoTime() + unit.toNanos(timeout);
    int idle = 0;

    while (true) {
      if (offer(e)) return true;

      long remaining = deadline - System.nanoTime();
      if (remaining <= 0) return false;

      // Progressive backoff
      if (idle < 100) {
        // spin
      } else if (idle < 1_000) {
        Thread.yield();
      } else {
        LockSupport.parkNanos(1_000L);
      }

      if (Thread.interrupted()) {
        throw new InterruptedException();
      }
      idle++;
    }
  }

  /**
   * Polls with a timeout using progressive backoff.
   *
   * @param timeout max wait time
   * @param unit time unit
   * @return the head element, or null if timed out
   * @throws InterruptedException if interrupted
   */
  public E poll(long timeout, @Nonnull TimeUnit unit) throws InterruptedException {
    if (timeout <= 0) {
      return poll();
    }

    final long deadline = System.nanoTime() + unit.toNanos(timeout);
    int idleCount = 0;

    while (true) {
      E e = poll();
      if (e != null) return e;

      long remaining = deadline - System.nanoTime();
      if (remaining <= 0) return null;

      if (idleCount < 100) {
        // spin
      } else if (idleCount < 1_000) {
        Thread.yield();
      } else {
        LockSupport.parkNanos(1_000L);
      }

      if (Thread.interrupted()) {
        throw new InterruptedException();
      }
      idleCount++;
    }
  }

  /**
   * Drains all available elements from the queue to a consumer.
   *
   * <p>This is efficient since it avoids repeated size() checks and returns immediately when empty.
   *
   * @param consumer a consumer to accept elements
   * @return number of elements drained
   */
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
  public int remainingCapacity() {
    return capacity - size();
  }

  /**
   * Returns the maximum queue capacity.
   *
   * @return number of total elements this queue can accept
   */
  public int capacity() {
    return capacity;
  }

  @Override
  public void put(E e) throws InterruptedException {
    throw new UnsupportedOperationException("Not implementing blocking operations for producers");
  }

  @Override
  public E take() throws InterruptedException {
    throw new UnsupportedOperationException("Not implementing blocking operations for consumers");
  }

  @Override
  public int drainTo(Collection<? super E> c) {
    return drainTo(c, Integer.MAX_VALUE);
  }

  @Override
  public int drainTo(Collection<? super E> c, int maxElements) {
    return drain(c::add, maxElements);
  }
}

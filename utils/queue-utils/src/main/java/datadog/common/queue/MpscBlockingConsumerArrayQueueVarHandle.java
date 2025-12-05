package datadog.common.queue;

import datadog.common.queue.padding.PaddedThread;
import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * Multiple-Producer, Single-Consumer bounded lock-free queue with blocking consumer support.
 * Producers are lock-free (CAS-based), consumer is wait-free. Uses VarHandles for memory ordering.
 *
 * @param <E> the type of elements stored
 */
public final class MpscBlockingConsumerArrayQueueVarHandle<E> extends MpscArrayQueueVarHandle<E>
    implements MessagePassingBlockingQueue<E> {

  private final PaddedThread consumerThread = new PaddedThread();

  /**
   * @param requestedCapacity queue capacity (rounded up to power of two)
   */
  public MpscBlockingConsumerArrayQueueVarHandle(int requestedCapacity) {
    super(requestedCapacity);
  }

  /**
   * Inserts element at tail if space available. Lock-free for producers competing via CAS. Uses
   * release semantics on buffer write to synchronize with consumer's acquire read.
   *
   * @param e the element to add (must not be null)
   * @return true if added, false if full
   */
  @Override
  public boolean offer(E e) {
    Objects.requireNonNull(e);

    long pLimit = this.producerLimit.getVolatile();
    long pIndex;

    do {
      pIndex = tail.getVolatile();

      if (pIndex >= pLimit) {
        final long cIndex = head.getVolatile();
        pLimit = cIndex + capacity;

        if (pIndex >= pLimit) {
          return false;
        }

        this.producerLimit.setVolatile(pLimit);
      }
    } while (!tail.compareAndSet(pIndex, pIndex + 1));

    final int index = arrayIndex(pIndex);

    // Release: synchronizes element write with consumer's acquire read
    ARRAY_HANDLE.setRelease(buffer, index, e);
    Thread c = consumerThread.getAndSet(null);
    if (c != null) {
      LockSupport.unpark(c);
    }
    return true;
  }

  /**
   * Batch operation claiming multiple slots with single CAS, then filling them. More efficient than
   * repeated offer() calls due to reduced CAS contention.
   *
   * @param s supplier of elements
   * @param limit max elements to add
   * @return actual number of elements added
   */
  @Override
  public int fill(Supplier<E> s, int limit) {
    if (null == s) {
      throw new IllegalArgumentException("supplier is null");
    }
    if (limit < 0) {
      throw new IllegalArgumentException("limit is negative:" + limit);
    }
    if (limit == 0) {
      return 0;
    }

    long pLimit = this.producerLimit.getVolatile();
    long pIndex;
    int actualLimit;

    do {
      pIndex = tail.getVolatile();
      long available = pLimit - pIndex;

      if (available <= 0) {
        final long cIndex = head.getVolatile();
        pLimit = cIndex + capacity;
        available = pLimit - pIndex;

        if (available <= 0) {
          return 0;
        }

        this.producerLimit.setVolatile(pLimit);
      }

      actualLimit = Math.min((int) available, limit);
    } while (!tail.compareAndSet(pIndex, pIndex + actualLimit));

    for (int i = 0; i < actualLimit; i++) {
      final int index = arrayIndex(pIndex + i);
      final E e = s.get();
      ARRAY_HANDLE.setRelease(buffer, index, e);
    }

    Thread c = consumerThread.getAndSet(null);
    if (c != null) {
      LockSupport.unpark(c);
    }

    return actualLimit;
  }

  /**
   * Retrieves and removes head element, or returns null if empty. Wait-free for single consumer.
   * Uses plain read for head (consumer-exclusive), acquire for buffer (syncs with producer's
   * release), and release for head write (ensures slot clear visible to producers).
   *
   * @return head element or null if empty
   */
  @Override
  @SuppressWarnings("unchecked")
  public E poll() {
    // Plain: consumer reads its own head, no synchronization needed
    final long cIndex = head.getPlain();
    final int index = arrayIndex(cIndex);

    final Object[] buffer = this.buffer;

    // Acquire: pairs with producer's setRelease
    Object e = ARRAY_HANDLE.getAcquire(buffer, index);

    if (e == null) {
      if (cIndex != tail.getVolatile()) {
        // Producer claimed slot but hasn't written yet - spin until element appears
        do {
          e = ARRAY_HANDLE.getAcquire(buffer, index);
        } while (e == null);
      } else {
        return null;
      }
    }

    // Release: ensures slot clear visible before head advance
    ARRAY_HANDLE.setRelease(buffer, index, null);

    // Release: synchronizes slot clear with producers reading head
    head.setRelease(cIndex + 1);

    return (E) e;
  }

  @Override
  public void put(E e) throws InterruptedException {
    if (!offer(e)) {
      throw new UnsupportedOperationException();
    }
  }

  @Override
  public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
    {
      if (offer(e)) {
        return true;
      }
      throw new UnsupportedOperationException();
    }
  }

  /**
   * Drains up to limit elements with timed wait. Single consumer only.
   *
   * @param c element consumer
   * @param limit max elements to drain
   * @param timeout max wait time
   * @param unit timeout unit
   * @return number of polled elements
   */
  public int drain(Consumer<E> c, final int limit, long timeout, TimeUnit unit)
      throws InterruptedException {
    if (limit == 0) {
      return 0;
    }
    final int drained = drain(c, limit);
    if (drained != 0) {
      return drained;
    }
    final E e = poll(timeout, unit);
    if (e == null) return 0;
    c.accept(e);
    return 1 + drain(c, limit - 1);
  }

  /**
   * @param timeout max wait time
   * @param unit timeout unit
   * @return head element or null if timeout
   */
  @Override
  public E poll(long timeout, TimeUnit unit) throws InterruptedException {
    E e = poll();
    if (e != null) {
      return e;
    }

    final long parkNanos = unit.toNanos(timeout);
    if (parkNanos <= 0) {
      return null;
    }

    parkUntilNext(parkNanos);

    return poll();
  }

  @Override
  public int remainingCapacity() {
    return capacity - size();
  }

  @Override
  public int drainTo(Collection<? super E> c) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int drainTo(Collection<? super E> c, int maxElements) {
    throw new UnsupportedOperationException();
  }

  @Override
  public E take() throws InterruptedException {
    E e;
    while ((e = poll()) == null) {
      parkUntilNext(-1);
    }
    return e;
  }

  /**
   * Parks consumer thread until element available or timeout elapses. Single consumer only.
   *
   * @param nanos max wait time; negative means indefinite
   */
  private void parkUntilNext(long nanos) throws InterruptedException {
    if (Thread.interrupted()) {
      throw new InterruptedException();
    }

    Thread current = Thread.currentThread();
    try {
      // Opaque: no ordering required for thread publication
      consumerThread.setOpaque(current);
      if (nanos <= 0) {
        LockSupport.parkNanos(this, Long.MAX_VALUE);
      } else {
        LockSupport.parkNanos(this, nanos);
      }
      if (Thread.interrupted()) {
        throw new InterruptedException();
      }
    } finally {
      consumerThread.setOpaque(null);
    }
  }
}

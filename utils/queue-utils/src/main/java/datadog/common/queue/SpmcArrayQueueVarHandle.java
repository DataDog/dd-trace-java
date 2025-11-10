package datadog.common.queue;

import java.util.Objects;
import java.util.concurrent.locks.LockSupport;

/**
 * A Single-Producer, Multiple-Consumer (SPMC) bounded, lock-free queue based on a circular array.
 *
 * <p>All operations are wait-free for the single producer and lock-free for consumers.
 *
 * @param <E> the element type
 */
final class SpmcArrayQueueVarHandle<E> extends BaseQueue<E> {
  // Padding around consumerLimit
  @SuppressWarnings("unused")
  private long p0, p1, p2, p3, p4, p5, p6;

  /** Cached consumer limit to avoid repeated volatile tail reads */
  private volatile long consumerLimit = 0L;

  // Padding around consumerLimit
  @SuppressWarnings("unused")
  private long q0, q1, q2, q3, q4, q5, q6;

  /**
   * Creates a new SPMC queue.
   *
   * @param requestedCapacity the desired capacity, rounded up to next power of two
   */
  public SpmcArrayQueueVarHandle(int requestedCapacity) {
    super(requestedCapacity);
  }

  @Override
  public boolean offer(E e) {
    Objects.requireNonNull(e);

    long currentTail = tail;
    long wrapPoint = currentTail - capacity;
    long currentHead = (long) HEAD_HANDLE.getVolatile(this);

    if (wrapPoint >= currentHead) {
      return false; // queue full
    }

    int index = (int) (currentTail & mask);

    // Release-store ensures that the element is visible to consumers
    ARRAY_HANDLE.setRelease(this.buffer, index, e);

    // Single-producer: simple volatile write to advance tail
    TAIL_HANDLE.setVolatile(this, currentTail + 1);
    return true;
  }

  @Override
  @SuppressWarnings("unchecked")
  public E poll() {
    final Object[] localBuffer = this.buffer;

    int spinCycles = 0;
    boolean parkOnSpin = (Thread.currentThread().getId() & 1) == 0;

    while (true) {
      long currentHead = (long) HEAD_HANDLE.getVolatile(this);
      long limit = consumerLimit; // cached tail

      if (currentHead >= limit) {
        // refresh limit once from tail volatile
        limit = (long) TAIL_HANDLE.getVolatile(this);
        if (currentHead >= limit) {
          return null; // queue empty
        }
        consumerLimit = limit; // update local cache
      }

      // Attempt to claim this slot
      if (!HEAD_HANDLE.compareAndSet(this, currentHead, currentHead + 1)) {
        // CAS failed. Backoff to reduce contention
        if ((spinCycles & 1) == 0) {
          Thread.onSpinWait();
        } else {
          if (parkOnSpin) {
            LockSupport.parkNanos(1);
          } else {
            Thread.yield();
          }
        }
        spinCycles++;
        continue;
      }

      int index = (int) (currentHead & mask);
      Object value;

      // Spin-wait until producer publishes
      while ((value = ARRAY_HANDLE.getAcquire(localBuffer, index)) == null) {
        Thread.onSpinWait();
      }

      // Clear slot for GC
      ARRAY_HANDLE.setOpaque(localBuffer, index, null);
      return (E) value;
    }
  }

  @Override
  @SuppressWarnings("unchecked")
  public E peek() {
    long currentHead = (long) HEAD_HANDLE.getVolatile(this);
    long currentTail = (long) TAIL_HANDLE.getVolatile(this);

    if (currentHead >= currentTail) return null;

    int index = (int) (currentHead & mask);
    return (E) ARRAY_HANDLE.getAcquire(buffer, index); // acquire-load ensures visibility
  }
}

package datadog.common.queue;

import datadog.common.queue.padding.PaddedSequence;
import java.util.Objects;
import java.util.concurrent.locks.LockSupport;

/**
 * A Multiple-Producer, Single-Consumer (MPSC) bounded lock-free queue using a circular array and
 * VarHandles.
 *
 * <p>All operations are wait-free for the consumer and lock-free for producers.
 *
 * @param <E> the type of elements stored
 */
class MpscArrayQueueVarHandle<E> extends BaseQueue<E> {
  /** Cached producer limit to reduce volatile head reads */
  protected final PaddedSequence producerLimit;

  /**
   * Creates a new MPSC queue.
   *
   * @param requestedCapacity the desired capacity, rounded up to next power of two
   */
  public MpscArrayQueueVarHandle(int requestedCapacity) {
    super(requestedCapacity);
    this.producerLimit = new PaddedSequence(capacity);
    ;
  }

  @Override
  public boolean offer(E e) {
    Objects.requireNonNull(e);

    // jctools does the same local copy to have the jitter optimise the accesses
    final Object[] localBuffer = this.buffer;

    long localProducerLimit = producerLimit.getVolatile();
    long cachedHead = 0L; // Local cache of head to reduce volatile reads

    int spinCycles = 0;
    boolean parkOnSpin = (Thread.currentThread().getId() & 1) == 0;

    while (true) {
      long currentTail = tail.getVolatile();

      // Check if producer limit exceeded
      if (currentTail >= localProducerLimit) {
        // Refresh head only when necessary
        cachedHead = head.getVolatile();
        localProducerLimit = cachedHead + capacity;

        if (currentTail >= localProducerLimit) {
          return false; // queue full
        }

        // Update producerLimit so other producers also benefit
        producerLimit.setVolatile(localProducerLimit);
      }

      // Attempt to claim a slot
      if (tail.compareAndSet(currentTail, currentTail + 1)) {
        final int index = (int) (currentTail & mask);

        // Release-store ensures producer's write is visible to consumer
        ARRAY_HANDLE.setRelease(localBuffer, index, e);
        return true;
      }

      // Backoff to reduce contention
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
    }
  }

  @Override
  @SuppressWarnings("unchecked")
  public final E poll() {
    final Object[] localBuffer = this.buffer;

    long currentHead = head.getOpaque();
    final int index = (int) (currentHead & mask);

    // Acquire-load ensures visibility of producer write
    Object value = ARRAY_HANDLE.getAcquire(localBuffer, index);
    if (value == null) {
      return null;
    }

    // Clear the slot without additional fence
    ARRAY_HANDLE.setOpaque(localBuffer, index, null);

    // Advance head using opaque write (consumer-only)
    head.setOpaque(currentHead + 1);

    return (E) value;
  }

  @Override
  @SuppressWarnings("unchecked")
  public final E peek() {
    final int index = (int) (head.getOpaque() & mask);
    return (E) ARRAY_HANDLE.getVolatile(buffer, index);
  }
}

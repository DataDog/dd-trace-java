package datadog.common.queue;

import java.util.Objects;

/**
 * A high-performance Single-Producer, Single-Consumer (SPSC) bounded queue using a circular buffer.
 *
 * @param <E> the type of elements held in this queue
 */
final class SpscArrayQueueVarHandle<E> extends BaseQueue<E> {
  // These caches eliminate redundant volatile reads
  private long cachedHead = 0L; // visible only to producer
  private long cachedTail = 0L; // visible only to consumer

  /**
   * Creates a new SPSC queue with the specified capacity. Capacity must be a power of two.
   *
   * @param requestedCapacity the desired capacity, rounded up to the next power of two if needed
   */
  public SpscArrayQueueVarHandle(int requestedCapacity) {
    super(requestedCapacity);
  }

  @Override
  public boolean offer(E e) {
    Objects.requireNonNull(e);

    final long currentTail = (long) TAIL_HANDLE.getOpaque(this);
    final int index = (int) (currentTail & mask);

    if (currentTail - cachedHead >= capacity) {
      // Refresh cached head (read from consumer side)
      cachedHead = (long) HEAD_HANDLE.getVolatile(this);
      if (currentTail - cachedHead >= capacity) {
        return false; // still full
      }
    }

    ARRAY_HANDLE.setRelease(buffer, index, e); // publish value
    TAIL_HANDLE.setOpaque(this, currentTail + 1); // relaxed tail update
    return true;
  }

  @Override
  @SuppressWarnings("unchecked")
  public E poll() {
    final long currentHead = (long) HEAD_HANDLE.getOpaque(this);
    final int index = (int) (currentHead & mask);

    if (currentHead >= cachedTail) {
      // refresh tail cache
      cachedTail = (long) TAIL_HANDLE.getVolatile(this);
      if (currentHead >= cachedTail) {
        return null; // still empty
      }
    }

    Object value = ARRAY_HANDLE.getAcquire(buffer, index);
    ARRAY_HANDLE.setOpaque(buffer, index, null); // clear slot
    HEAD_HANDLE.setOpaque(this, currentHead + 1); // relaxed head update
    return (E) value;
  }

  @Override
  @SuppressWarnings("unchecked")
  public E peek() {
    final int index = (int) ((long) HEAD_HANDLE.getOpaque(this) & mask);
    return (E) ARRAY_HANDLE.getVolatile(buffer, index);
  }
}

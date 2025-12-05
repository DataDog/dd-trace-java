package datadog.common.queue;

import java.util.Objects;

/**
 * Single-Producer, Single-Consumer bounded queue using a circular buffer. Uses cached indices to
 * eliminate redundant volatile reads and look-ahead optimization to batch producer index updates.
 *
 * @param <E> the type of elements held in this queue
 */
public final class SpscArrayQueueVarHandle<E> extends BaseQueue<E> {
  private long cachedHead = 0L; // visible only to producer
  private long cachedTail = 0L; // visible only to consumer
  private final int lookAheadStep;

  /**
   * @param requestedCapacity queue capacity (rounded up to power of two)
   */
  public SpscArrayQueueVarHandle(int requestedCapacity) {
    super(requestedCapacity);
    // This should go in a common place because today is defined under jctools-channel
    // but here I'd like not to draw that dependency
    lookAheadStep =
        Math.min(capacity / 4, Integer.getInteger("jctools.spsc.max.lookahead.step", 4096));
  }

  /**
   * @param e element to add
   * @return false if full, true if added
   */
  @Override
  public boolean offer(E e) {
    Objects.requireNonNull(e);

    final long currentTail = tail.getPlain();
    final int index = arrayIndex(currentTail);

    if (currentTail - cachedHead >= capacity) {
      cachedHead = head.getAcquire(); // Acquire: pairs with consumer's setRelease
      if (currentTail - cachedHead >= capacity) {
        return false;
      }
    }

    ARRAY_HANDLE.setRelease(buffer, index, e); // Release: ensures visibility before tail update
    tail.setRelease(currentTail + 1); // Release: publishes element to consumer
    return true;
  }

  /**
   * @return head element or null if empty
   */
  @Override
  @SuppressWarnings("unchecked")
  public E poll() {
    final long currentHead = head.getPlain();
    final int index = arrayIndex(currentHead);

    if (currentHead >= cachedTail) {
      cachedTail = tail.getAcquire(); // Acquire: pairs with producer's setRelease
      if (currentHead >= cachedTail) {
        return null;
      }
    }
    // Volatile: ensures visibility of producer writes
    final E value = (E) ARRAY_HANDLE.getVolatile(buffer, index);
    // Release: publishes slot availability to producer
    ARRAY_HANDLE.setRelease(buffer, index, null);
    // Release: maintains ordering
    head.setRelease(currentHead + 1);

    return value;
  }

  /**
   * @return head element or null if empty
   */
  @Override
  @SuppressWarnings("unchecked")
  public E peek() {
    final long currentHead = head.getPlain();

    if (currentHead >= cachedTail) {
      cachedTail = tail.getAcquire(); // Acquire: pairs with producer's setRelease
      if (currentHead >= cachedTail) {
        return null;
      }
    }

    final int index = arrayIndex(currentHead);
    return (E)
        ARRAY_HANDLE.getVolatile(buffer, index); // Volatile: ensures visibility of producer writes
  }

  /**
   * Drains up to limit elements from the queue. Optimized batch operation that updates consumer
   * index per element with release semantics.
   *
   * @param consumer element consumer
   * @param limit max elements to drain
   * @return actual count drained
   */
  @Override
  public int drain(Consumer<E> consumer, int limit) {
    if (null == consumer) {
      throw new IllegalArgumentException("consumer is null");
    }
    if (limit < 0) {
      throw new IllegalArgumentException("limit is negative: " + limit);
    }
    if (limit == 0) {
      return 0;
    }

    final Object[] localBuffer = this.buffer;
    final long consumerIndex = head.getPlain();

    for (int i = 0; i < limit; i++) {
      final long index = consumerIndex + i;
      final int offset = arrayIndex(index);

      final E e =
          (E) ARRAY_HANDLE.getVolatile(localBuffer, offset); // Volatile: sees producer writes
      if (e == null) {
        return i;
      }

      ARRAY_HANDLE.setRelease(localBuffer, offset, null); // Release: publishes slot availability
      head.setRelease(index + 1); // Release: maintains ordering for size()

      consumer.accept(e);
    }

    return limit;
  }

  /**
   * Fills the queue with elements from the supplier up to the specified limit. Uses look-ahead
   * optimization to batch producer index updates and reduce volatile writes.
   *
   * @param s supplier of elements
   * @param limit max elements to add
   * @return actual count added
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

    final Object[] localBuffer = this.buffer;
    final int lookAheadStep = this.lookAheadStep;
    long producerIndex = tail.getPlain();

    for (int i = 0; i < limit; i++) {
      final long index = producerIndex + i;
      final int lookAheadOffset = arrayIndex(index + lookAheadStep);

      if (ARRAY_HANDLE.getVolatile(localBuffer, lookAheadOffset)
          == null) { // Volatile: checks slot availability
        // Look-ahead optimization for SPSC fill().
        // If slot N steps ahead is free, we know slots 0..N are also free (SPSC property).
        // Batch-fill up to N elements, updating tail per element for consumer visibility.
        // Reduces volatile reads
        int lookAheadLimit = Math.min(lookAheadStep, limit - i);

        for (int j = 0; j < lookAheadLimit; j++) {
          final int offset = arrayIndex(index + j);
          E e = s.get();
          ARRAY_HANDLE.setRelease(localBuffer, offset, e); // Release: ensures visibility
          tail.setRelease(index + j + 1); // Release: publishes element to consumer
        }

        i += lookAheadLimit - 1;
      } else {
        final int offset = arrayIndex(index);
        if (ARRAY_HANDLE.getVolatile(localBuffer, offset)
            != null) { // Volatile: checks slot availability
          return i;
        }

        E e = s.get();
        ARRAY_HANDLE.setRelease(localBuffer, offset, e); // Release: ensures visibility
        tail.setRelease(index + 1); // Release: publishes element to consumer
      }
    }

    return limit;
  }

  /**
   * @param e element to add
   * @return false if full, true if added
   */
  @Override
  public boolean relaxedOffer(E e) {
    return offer(e);
  }

  /**
   * @return head element or null if empty
   */
  @Override
  public E relaxedPoll() {
    return poll();
  }

  /**
   * @return head element or null if empty
   */
  @Override
  public E relaxedPeek() {
    return peek();
  }
}

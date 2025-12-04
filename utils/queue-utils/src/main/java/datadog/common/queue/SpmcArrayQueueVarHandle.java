package datadog.common.queue;

import datadog.common.queue.padding.PaddedLong;
import java.util.Objects;
import org.jctools.queues.MessagePassingQueue;

/**
 * Single-Producer Multiple-Consumer bounded lock-free queue on circular array. Producer operations
 * are wait-free; consumer operations are lock-free. Producer uses plain reads and release writes;
 * consumers use CAS and volatile reads.
 *
 * @param <E> the element type
 */
public final class SpmcArrayQueueVarHandle<E> extends BaseQueue<E>
    implements MessagePassingQueue<E> {
  /** Cached producer limit to reduce volatile tail reads. */
  private final PaddedLong consumerLimit = new PaddedLong();

  /** @param requestedCapacity queue capacity (rounded up to power of two) */
  public SpmcArrayQueueVarHandle(int requestedCapacity) {
    super(requestedCapacity);
  }

  /**
   * Adds element to queue. Spins on bubble (slot claimed but not yet cleared).
   *
   * @param e element to add
   * @return false if full, true if added
   */
  @Override
  public boolean offer(E e) {
    Objects.requireNonNull(e);

    final Object[] localBuffer = this.buffer;
    // Plain: single producer, no contention
    final long currentTail = tail.getPlain();
    final int index = arrayIndex(currentTail);

    // Acquire: pairs with consumer's setRelease(null)
    if (ARRAY_HANDLE.getAcquire(localBuffer, index) != null) {
      // Acquire: synchronize with consumer's setRelease
      long size = currentTail - head.getAcquire();

      if (size > mask) {
        return false;
      } else {
        // Consumer claimed this slot via CAS but hasn't cleared it yet.
        // We know slot will be freed (head advanced) so spin until null visible.
        // This is wait-free: bounded by consumer's clear latency.
        while (ARRAY_HANDLE.getAcquire(localBuffer, index) != null) {
          Thread.onSpinWait();
        }
      }
    }

    // Release: publish element to consumers
    ARRAY_HANDLE.setRelease(localBuffer, index, e);

    // Release: publish tail advance after element write
    tail.setRelease(currentTail + 1);
    return true;
  }

  /**
   * Batch adds up to limit elements. Single producer optimized: no CAS needed.
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
    // Plain: single producer, no contention
    long producerIndex = tail.getPlain();

    for (int i = 0; i < limit; i++) {
      int index = arrayIndex(producerIndex);

      // Acquire: check slot availability
      if (ARRAY_HANDLE.getAcquire(localBuffer, index) != null) {
        return i;
      }

      producerIndex++;

      E e = s.get();
      // Release: publish element to consumers
      ARRAY_HANDLE.setRelease(localBuffer, index, e);

      // Release: publish tail advance after element write
      tail.setRelease(producerIndex);
    }

    return limit;
  }

  /**
   * Batch drains up to limit elements using single CAS to claim multiple slots. Reduces contention
   * vs repeated poll() calls.
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
    long producerIndexCache = consumerLimit.getAcquire();
    int adjustedLimit = 0;
    long currentConsumerIndex;

    do {
      // Volatile: see other consumers' CAS updates
      currentConsumerIndex = head.getVolatile();

      if (currentConsumerIndex >= producerIndexCache) {
        // Acquire: pairs with producer's setRelease
        long producerIndex = tail.getAcquire();
        if (currentConsumerIndex >= producerIndex) {
          return 0;
        }
        // Plain: cache update, no visibility needed
        producerIndexCache = producerIndex;
        consumerLimit.setPlain(producerIndex);
      }

      int remaining = (int) (producerIndexCache - currentConsumerIndex);
      adjustedLimit = Math.min(remaining, limit);
      // Single CAS claims multiple slots for batch drain.
      // Reduces contention vs limit separate poll() CAS operations.
      // Failed CAS means concurrent consumer - retry with fresh head.
    } while (!head.compareAndSet(currentConsumerIndex, currentConsumerIndex + adjustedLimit));

    for (int i = 0; i < adjustedLimit; i++) {
      final int index = arrayIndex(currentConsumerIndex + i);

      // Plain: slot owned after CAS, element visible via producer's release
      final E e = (E) ARRAY_HANDLE.get(localBuffer, index);

      // Release: publish slot clear to producer
      ARRAY_HANDLE.setRelease(localBuffer, index, null);

      consumer.accept(e);
    }

    return adjustedLimit;
  }

  /**
   * Removes and returns head element using CAS.
   *
   * @return head element or null if empty
   */
  @Override
  @SuppressWarnings("unchecked")
  public E poll() {
    final Object[] localBuffer = this.buffer;
    long currentHead;
    long producerIndexCache = consumerLimit.getAcquire();

    do {
      // Volatile read needed for SPMC.
      currentHead = head.getVolatile();

      if (currentHead >= producerIndexCache) {
        // Acquire: pairs with producer's setRelease
        long producerIndex = tail.getAcquire();
        if (currentHead >= producerIndex) {
          return null;
        }
        // Plain: cache update, no visibility needed
        producerIndexCache = producerIndex;
        consumerLimit.setPlain(producerIndex);
      }
    } while (!head.compareAndSet(currentHead, currentHead + 1));

    final int index = arrayIndex(currentHead);
    // Plain: slot owned after CAS, element visible via producer's release
    final E e = (E) ARRAY_HANDLE.get(localBuffer, index);

    // Release: publish slot clear to producer
    ARRAY_HANDLE.setRelease(localBuffer, index, null);

    return e;
  }

  /**
   * Returns head element without removing. Uses sandwich (head-element-head) to ensure consistency.
   *
   * @return head element or null if empty
   */
  @Override
  @SuppressWarnings("unchecked")
  public E peek() {
    final Object[] localBuffer = this.buffer;
    long producerIndexCache = consumerLimit.getAcquire();
    long currentConsumerIndex;
    // Volatile: see other consumers' CAS updates
    long nextConsumerIndex = head.getVolatile();
    E e;

    do {
      currentConsumerIndex = nextConsumerIndex;

      if (currentConsumerIndex >= producerIndexCache) {
        // Acquire: pairs with producer's setRelease
        long currProducerIndex = tail.getAcquire();
        if (currentConsumerIndex >= currProducerIndex) {
          return null;
        }
        // Plain: cache update, no visibility needed
        producerIndexCache = currProducerIndex;
        consumerLimit.setPlain(currProducerIndex);
      }

      int index = arrayIndex(currentConsumerIndex);
      // Acquire: pairs with producer's setRelease
      e = (E) ARRAY_HANDLE.getAcquire(localBuffer, index);

      // "Sandwich" pattern for consistent peek in SPMC.
      // Read head-element-head to detect races with concurrent poll().
      // If head changed, element we read might have been consumed - retry.
      nextConsumerIndex = head.getVolatile();

    } while (e == null || nextConsumerIndex != currentConsumerIndex);

    return e;
  }

  /**
   * Fast offer that rejects early if slot occupied, skipping full queue check.
   *
   * @param e element to add
   * @return false if slot occupied, true if added
   */
  @Override
  public boolean relaxedOffer(E e) {
    Objects.requireNonNull(e);

    final Object[] localBuffer = this.buffer;
    // Plain: single producer, no contention
    final long producerIndex = tail.getPlain();
    final int index = arrayIndex(producerIndex);

    // Acquire: check slot availability
    if (ARRAY_HANDLE.getAcquire(localBuffer, index) != null) {
      return false;
    }

    // Release: publish element to consumers
    ARRAY_HANDLE.setRelease(localBuffer, index, e);

    // Release: publish tail advance after element write
    tail.setRelease(producerIndex + 1);

    return true;
  }

  /**
   * Delegates to poll() as CAS is required for SPMC; no relaxation possible.
   *
   * @return head element or null if empty
   */
  @Override
  public E relaxedPoll() {
    return poll();
  }

  /**
   * Simple sandwich peek without emptiness check. May return null on race.
   *
   * @return head element or null if empty/race
   */
  @Override
  @SuppressWarnings("unchecked")
  public E relaxedPeek() {
    final Object[] localBuffer = this.buffer;
    long currentConsumerIndex;
    // Volatile: see other consumers' CAS updates
    long nextConsumerIndex = head.getVolatile();
    E e;

    do {
      currentConsumerIndex = nextConsumerIndex;
      int index = arrayIndex(currentConsumerIndex);
      // Acquire: pairs with producer's setRelease
      e = (E) ARRAY_HANDLE.getAcquire(localBuffer, index);
      // Volatile: recheck head for sandwich consistency
      nextConsumerIndex = head.getVolatile();
    } while (nextConsumerIndex != currentConsumerIndex);

    return e;
  }
}

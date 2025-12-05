package datadog.common.queue;

import datadog.common.queue.padding.PaddedLong;
import java.util.Objects;

/**
 * MPSC bounded queue using VarHandles for lock-free producers and wait-free consumer. Producers
 * compete via CAS on tail, consumer has exclusive access to head. Memory ordering:
 * setRelease/getAcquire pairs for element visibility, plain access where exclusive.
 */
public class MpscArrayQueueVarHandle<E> extends BaseQueue<E> {
  /**
   * Cached producer limit to avoid volatile head reads. Updated lazily when exceeded. Memory
   * ordering: getVolatile/setRelease (racy updates benign).
   */
  protected final PaddedLong producerLimit;

  /**
   * @param requestedCapacity queue capacity (rounded up to power of two)
   */
  public MpscArrayQueueVarHandle(int requestedCapacity) {
    super(requestedCapacity);
    this.producerLimit = new PaddedLong(capacity);
  }

  /**
   * Inserts element at tail if space available. Lock-free for producers (CAS on tail). Memory
   * ordering: tail CAS (full barrier), element setRelease pairs with consumer getAcquire.
   *
   * @param e element to add
   * @return true if added, false if full
   */
  @Override
  public boolean offer(E e) {
    Objects.requireNonNull(e);

    long pLimit = this.producerLimit.getVolatile();
    long pIndex;

    do {
      pIndex = tail.getVolatile(); // Volatile: see all prior producer claims

      if (pIndex >= pLimit) {
        // Producer limit caching optimization.
        // Cache consumer head to avoid volatile read on every offer.
        // Racy updates benign: worst case we re-check head unnecessarily.
        final long cIndex = head.getVolatile(); // Volatile: see consumer progress
        pLimit = cIndex + capacity;

        if (pIndex >= pLimit) {
          return false;
        }

        this.producerLimit.setRelease(pLimit);
      }
    } while (!tail.compareAndSet(pIndex, pIndex + 1)); // CAS: claim slot atomically

    final int index = arrayIndex(pIndex);
    ARRAY_HANDLE.setRelease(buffer, index, e); // Release: ensure element visible to consumer
    return true;
  }

  /**
   * Batch insert up to limit elements. Single CAS claims multiple slots for efficiency. Memory
   * ordering: same as offer, setRelease per element.
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

        this.producerLimit.setRelease(pLimit);
      }

      actualLimit = Math.min((int) available, limit);
    } while (!tail.compareAndSet(pIndex, pIndex + actualLimit));

    for (int i = 0; i < actualLimit; i++) {
      final int index = arrayIndex(pIndex + i);
      final E e = s.get();
      ARRAY_HANDLE.setRelease(buffer, index, e); // Release: ensure visibility
    }

    return actualLimit;
  }

  /**
   * Batch drain up to limit elements. Optimized for single consumer. Memory ordering: plain head
   * read (exclusive), getVolatile for elements, head setRelease per element.
   *
   * @param consumer element consumer
   * @param limit max elements to drain
   * @return actual number of elements drained
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
    final long cIndex = head.getPlain(); // Plain: consumer exclusive

    for (int i = 0; i < limit; i++) {
      final long index = cIndex + i;
      final int offset = arrayIndex(index);

      final E e = (E) ARRAY_HANDLE.getVolatile(localBuffer, offset);
      if (e == null) {
        return i;
      }

      ARRAY_HANDLE.set(localBuffer, offset, null); // Plain: consumer exclusive
      head.setRelease(index + 1); // Release: ensure clear visible before head advances

      consumer.accept(e);
    }

    return limit;
  }

  /**
   * Retrieves and removes head element, or null if empty. Wait-free for consumer. Memory ordering:
   * plain head read (exclusive), getAcquire pairs with producer setRelease, head setRelease ensures
   * clear visible. Spins on getAcquire if producer claimed slot but hasn't written yet (head !=
   * tail but element null).
   *
   * @return head element or null if empty
   */
  @Override
  @SuppressWarnings("unchecked")
  public E poll() {
    final long cIndex = head.getPlain(); // Plain: consumer exclusive
    final int index = arrayIndex(cIndex);
    final Object[] buffer = this.buffer;

    Object e = ARRAY_HANDLE.getAcquire(buffer, index); // Acquire: pairs with producer setRelease

    if (e == null) {
      if (cIndex != tail.getVolatile()) { // Volatile: see latest tail
        // Producer claimed slot via CAS but hasn't written element yet.
        // We know element will appear (tail advanced) so spin until visible.
        // This is wait-free: bounded by producer's write latency.
        do {
          e = ARRAY_HANDLE.getAcquire(buffer, index);
        } while (e == null);
      } else {
        return null;
      }
    }

    ARRAY_HANDLE.setRelease(
        buffer, index, null); // Release: ensure clear visible before head advances
    head.setRelease(cIndex + 1); // Release: cheaper than setVolatile, sufficient for correctness

    return (E) e;
  }

  /**
   * Retrieves but does not remove head element, or null if empty. Wait-free for consumer. Memory
   * ordering: same as poll but no writes. Spins if producer in-flight.
   *
   * @return head element or null if empty
   */
  @Override
  @SuppressWarnings("unchecked")
  public final E peek() {
    final long cIndex = head.getPlain(); // Plain: consumer exclusive
    final int index = arrayIndex(cIndex);
    final Object[] buffer = this.buffer;

    Object e = ARRAY_HANDLE.getAcquire(buffer, index); // Acquire: pairs with producer setRelease

    if (e == null) {
      if (cIndex != tail.getVolatile()) { // Volatile: check if producer in-flight
        do {
          e = ARRAY_HANDLE.getAcquire(buffer, index);
        } while (e == null);
      } else {
        return null;
      }
    }
    return (E) e;
  }

  /**
   * Relaxed offer delegates to regular offer for MPSC correctness. Multiple producers require CAS
   * and release semantics for proper visibility.
   *
   * @param e element to add
   * @return true if added, false if full
   */
  @Override
  public boolean relaxedOffer(E e) {
    return offer(e);
  }

  /**
   * Relaxed poll omits spin-wait. Returns null immediately if element not visible. Memory ordering:
   * plain head (exclusive), getAcquire for element, setRelease for head.
   *
   * @return head element or null if empty/not yet visible
   */
  @Override
  @SuppressWarnings("unchecked")
  public E relaxedPoll() {
    final long cIndex = head.getPlain(); // Plain: consumer exclusive
    final int index = arrayIndex(cIndex);
    final Object[] buffer = this.buffer;

    Object e = ARRAY_HANDLE.getAcquire(buffer, index); // Acquire: pairs with producer setRelease

    if (e == null) {
      return null; // No spin-wait in relaxed variant
    }

    ARRAY_HANDLE.set(buffer, index, null); // Plain: consumer exclusive
    head.setRelease(cIndex + 1); // Release: ensure clear visible

    return (E) e;
  }

  /**
   * Relaxed peek omits spin-wait. Returns null immediately if element not visible. Memory ordering:
   * plain head (exclusive), getAcquire for element.
   *
   * @return head element or null if empty/not yet visible
   */
  @Override
  @SuppressWarnings("unchecked")
  public E relaxedPeek() {
    final long cIndex = head.getPlain(); // Plain: consumer exclusive
    final int index = arrayIndex(cIndex);
    final Object[] buffer = this.buffer;

    Object e = ARRAY_HANDLE.getAcquire(buffer, index); // Acquire: pairs with producer setRelease
    return (E) e;
  }
}

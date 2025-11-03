package datadog.trace.util.queue;

import static datadog.trace.util.BitUtils.nextPowerOfTwo;

import java.util.AbstractQueue;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import javax.annotation.Nonnull;

/**
 * Multiple-Producer, Single-Consumer (MPSC) bounded queue based on a circular array.
 *
 * <p>This queue is optimized for high-performance concurrent access where multiple threads
 * (producers) can safely enqueue items concurrently, while a single thread (consumer) dequeues
 * them.
 *
 * <p>Producers leverage a lock free CAS loop to win the race. Fields are padded to minimize cache
 * line false sharing.
 *
 * @param <E> the type of elements held in this queue
 */
public class MpscArrayQueue<E> extends AbstractQueue<E> {

  /** Capacity of the queue, always a power of two for efficient modulo indexing */
  protected final int capacity;

  /** Mask used to convert a sequence number to a circular array index (index = pos & mask) */
  private final int mask;

  /** Array buffer to store the elements; uses AtomicReferenceArray for atomic slot updates */
  private final AtomicReferenceArray<E> buffer;

  // Padding
  @SuppressWarnings("unused")
  private long p0, p1, p2, p3, p4, p5, p6;

  /** Tail index: the next slot to insert for producers */
  private volatile long tail = 0L;

  // Padding
  @SuppressWarnings("unused")
  private long q0, q1, q2, q3, q4, q5, q6;

  /** Atomic updater to perform lock-free CAS on tail */
  private static final AtomicLongFieldUpdater<MpscArrayQueue> TAIL_UPDATER =
      AtomicLongFieldUpdater.newUpdater(MpscArrayQueue.class, "tail");

  // Padding
  @SuppressWarnings("unused")
  private long p10, p11, p12, p13, p14, p15, p16;

  /** Head index: the next slot to consume for the single consumer */
  private volatile long head = 0L;

  // Padding
  @SuppressWarnings("unused")
  private long q10, q11, q12, q13, q14, q15, q16;

  // ======================== Constructor ========================

  /**
   * Creates a new MPSC queue with the specified capacity. Capacity will be rounded up to the next
   * power of two for efficient modulo operations.
   *
   * @param capacity the desired maximum number of elements
   */
  public MpscArrayQueue(int capacity) {
    this.capacity = nextPowerOfTwo(capacity);
    this.mask = this.capacity - 1;
    this.buffer = new AtomicReferenceArray<>(this.capacity);
  }

  // ======================== OFFER ========================

  /**
   * Adds the specified element to the queue if space is available.
   *
   * <p>Multiple producers may safely call this concurrently. Uses a CAS loop to claim a slot and
   * {@link AtomicReferenceArray#lazySet(Object)} to publish the element. If the queue is full,
   * returns {@code false}.
   *
   * @param e the element to add
   * @return {@code true} if successful, {@code false} if queue is full
   * @throws NullPointerException if {@code e} is null
   */
  @Override
  public boolean offer(E e) {
    if (e == null) {
      throw new NullPointerException();
    }

    while (true) {
      long currentTail = tail;
      int index = (int) (currentTail & mask);

      // Check if slot is free
      if (buffer.get(index) != null) {
        return false; // queue full
      }

      // Attempt to claim slot using CAS
      if (TAIL_UPDATER.compareAndSet(this, currentTail, currentTail + 1)) {
        // Use lazySet for release semantics (avoids full volatile write)
        buffer.lazySet(index, e);
        return true;
      }

      // CAS failed, brief backoff to reduce contention
      // Note: I found parkNanos more CPU friendly than Thread.yields
      LockSupport.parkNanos(1);
    }
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
    final long deadline = System.nanoTime() + unit.toNanos(timeout);
    int idleCount = 0;

    while (true) {
      if (offer(e)) {
        return true; // successfully inserted
      }

      long remaining = deadline - System.nanoTime();
      if (remaining <= 0) {
        return false; // timeout
      }

      // Progressive backoff
      if (idleCount < 100) {
        // spin (busy-wait)
      } else if (idleCount < 1_000) {
        Thread.yield(); // give up CPU to other threads
      } else {
        // park for a short duration, up to 1 ms
        LockSupport.parkNanos(Math.min(remaining, 1_000_000L));
      }

      idleCount++;

      if (Thread.interrupted()) {
        throw new InterruptedException();
      }
    }
  }

  /**
   * Removes and returns the head of the queue, or null if empty.
   *
   * <p>Only a single consumer may call this. Advances the head and frees the slot.
   *
   * @return the head element, or null if empty
   */
  @Override
  public E poll() {
    long currentHead = head;
    int index = (int) (currentHead & mask);
    E value = buffer.get(index);

    if (value == null) {
      return null;
    }

    // Mark slot free with lazySet (release semantics)
    buffer.lazySet(index, null);
    head = currentHead + 1; // advance head
    return value;
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
    final long deadline = System.nanoTime() + unit.toNanos(timeout);
    int idleCount = 0;

    while (true) {
      E value = poll();
      if (value != null) {
        return value;
      }

      long remaining = deadline - System.nanoTime();
      if (remaining <= 0) {
        return null;
      }

      // Progressive backoff
      if (idleCount < 100) {
        // spin
      } else if (idleCount < 1_000) {
        Thread.yield();
      } else {
        LockSupport.parkNanos(Math.min(remaining, 1_000_000L));
      }
      idleCount++;

      if (Thread.interrupted()) {
        throw new InterruptedException();
      }
    }
  }

  /** Returns but does not remove the head element. */
  @Override
  public E peek() {
    int index = (int) (head & mask);
    return buffer.get(index);
  }

  @Override
  public int size() {
    long currentTail = tail;
    long currentHead = head;
    return (int) (currentTail - currentHead);
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
   * Returns the remaining capacity.
   *
   * @return number of additional elements this queue can accept
   */
  public int remainingCapacity() {
    return capacity - (int) (tail - head);
  }

  @Override
  public Iterator<E> iterator() {
    throw new UnsupportedOperationException();
  }
}

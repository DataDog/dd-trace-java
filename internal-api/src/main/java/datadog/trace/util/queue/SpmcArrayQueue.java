package datadog.trace.util.queue;

import static datadog.trace.util.BitUtils.nextPowerOfTwo;

import java.util.AbstractQueue;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

/**
 * A Single-Producer, Multiple-Consumer (SPMC) bounded queue based on a circular array.
 *
 * <p>This queue allows one producer to enqueue items concurrently with multiple consumers dequeuing
 * them. It is lock-free for the producer, and uses CAS on the consumer side to allow multiple
 * consumers safely.
 *
 * <p>Internally, the queue maintains a padded head and tail index to minimize false sharing, and
 * uses {@link AtomicReferenceArray} to store elements safely across threads.
 *
 * @param <E> the type of elements held in this queue
 */
public class SpmcArrayQueue<E> extends AbstractQueue<E> {

  /** The capacity of the queue (must be a power of two) */
  protected final int capacity;

  /** Mask for fast modulo operation (index = pos & mask) */
  private final int mask;

  /** Array buffer storing elements */
  private final AtomicReferenceArray<E> buffer;

  @SuppressWarnings("unused")
  private long p0, p1, p2, p3, p4, p5, p6;

  /** Tail index: next slot to be written by producer */
  private volatile long tail = 0L;

  @SuppressWarnings("unused")
  private long q0, q1, q2, q3, q4, q5, q6;
  @SuppressWarnings("unused")
  private long p10, p11, p12, p13, p14, p15, p16;

  /** Head index: next slot to be claimed by any consumer */
  private volatile long head = 0L;

  @SuppressWarnings("unused")
  private long q10, q11, q12, q13, q14, q15, q16;

  /** CAS updater for head to allow multiple consumers to claim elements safely */
  private static final AtomicLongFieldUpdater<SpmcArrayQueue> HEAD_UPDATER =
      AtomicLongFieldUpdater.newUpdater(SpmcArrayQueue.class, "head");

  /**
   * Constructs a new SPMC queue with the given capacity.. Capacity will be rounded up to the next
   * power of two for efficient modulo operations.
   *
   * @param capacity the desired maximum number of elements
   */
  public SpmcArrayQueue(int capacity) {
    this.capacity = nextPowerOfTwo(capacity);
    this.mask = capacity - 1;
    this.buffer = new AtomicReferenceArray<>(capacity);
  }

  /**
   * Adds the specified element to the queue if space is available.
   *
   * <p>Only one producer is allowed. The producer uses simple volatile writes (lazySet) to publish
   * elements, ensuring memory visibility for consumers.
   *
   * @param e the element to add
   * @return true if the element was added, false if the queue is full
   * @throws NullPointerException if the element is null
   */
  @Override
  public boolean offer(E e) {
    if (e == null) throw new NullPointerException();

    long currentTail = tail;
    int index = (int) (currentTail & mask);

    if (buffer.get(index) != null) {
      return false; // queue full
    }

    // Producer increments tail first to claim the slot
    tail = currentTail + 1;

    // Use lazySet to publish the element without forcing a full memory fence
    buffer.lazySet(index, e);
    return true;
  }

  /**
   * Removes and returns the head element of the queue, or {@code null} if empty.
   *
   * <p>Multiple consumers can safely call this concurrently. Each consumer uses CAS on the head
   * index to claim a slot. Only the successful consumer sets the element to null.
   *
   * @return the head element, or {@code null} if the queue is empty
   */
  @Override
  public E poll() {
    while (true) {
      long currentHead = head;
      int index = (int) (currentHead & mask);
      E value = buffer.get(index);

      if (value == null) {
        return null; // empty
      }

      // CAS ensures only one consumer claims this slot
      if (HEAD_UPDATER.compareAndSet(this, currentHead, currentHead + 1)) {
        // mark slot free after claiming it
        buffer.lazySet(index, null);
        return value;
      }

      // CAS failed: another consumer claimed it; retry
      LockSupport.parkNanos(1);
    }
  }

  /**
   * Returns, but does not remove, the head of the queue.
   *
   * @return the head element, or {@code null} if empty
   */
  @Override
  public E peek() {
    int index = (int) (head & mask);
    return buffer.get(index);
  }

  /**
   * Returns the number of elements in the queue.
   *
   * <p>Approximate: may not be exact under concurrent access.
   *
   * @return current size of the queue
   */
  @Override
  public int size() {
    return (int) (tail - head);
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
   * Drains all available elements from the queue to the specified consumer.
   *
   * <p>This method repeatedly calls {@link #poll()} until the queue is empty.
   *
   * @param consumer the consumer to accept elements
   * @return number of elements drained
   */
  public int drain(Consumer<E> consumer) {
    return drain(consumer, Integer.MAX_VALUE);
  }

  /**
   * Drains up to {@code limit} elements from the queue to the specified consumer.
   *
   * <p>Useful for batch processing. This avoids frequent CAS operations by handling multiple
   * elements in a single call.
   *
   * @param consumer the consumer to accept elements
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
}

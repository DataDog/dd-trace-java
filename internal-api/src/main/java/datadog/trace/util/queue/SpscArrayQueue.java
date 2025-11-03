package datadog.trace.util.queue;

import static datadog.trace.util.BitUtils.nextPowerOfTwo;

import java.util.AbstractQueue;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

/**
 * A high-performance Single-Producer Single-Consumer (SPSC) bounded queue based on a circular
 * array.
 *
 * <p>This queue is designed for scenarios where exactly one producer thread offers elements and one
 * consumer thread polls elements. It uses a plain {@code Object[]} buffer with volatile head and
 * tail indices and padded fields to minimize false sharing.
 *
 * @param <E> element type
 */
public final class SpscArrayQueue<E> extends AbstractQueue<E> {

  private final int mask;
  private final Object[] buffer;

  // ========================== Padded tail (producer index) ==========================
  @SuppressWarnings("unused")
  private long p0, p1, p2, p3, p4, p5, p6;
  private volatile long tail = 0L;
  @SuppressWarnings("unused")
  private long q0, q1, q2, q3, q4, q5, q6;

  // ========================== Padded head (consumer index) ==========================
  @SuppressWarnings("unused")
  private long r0, r1, r2, r3, r4, r5, r6;
  private volatile long head = 0L;
  @SuppressWarnings("unused")
  private long s0, s1, s2, s3, s4, s5, s6;

  /**
   * Constructs a new bounded Single producer single consumer queue.
   *
   * @param capacity the maximum number of elements the queue can hold. Will be rounded to the next
   *     power of two if not yet.
   */
  public SpscArrayQueue(int capacity) {
    final int roundedCap = nextPowerOfTwo(capacity);
    this.mask = roundedCap - 1;
    this.buffer = new Object[roundedCap];
  }

  /**
   * Attempts to add the specified element to this queue. Returns {@code false} if the queue is
   * full.
   *
   * @param e element to add (must not be null)
   * @return {@code true} if successfully added; {@code false} if full
   */
  @Override
  public boolean offer(E e) {
    if (e == null) {
      throw new NullPointerException();
    }

    final long currentTail = tail;
    final int index = (int) (currentTail & mask);

    // Check if slot is still occupied — if so, queue is full
    if (buffer[index] != null) {
      return false;
    }

    buffer[index] = e; // plain write (safe for SPSC)
    tail = currentTail + 1; // volatile write to publish
    return true;
  }

  /** Retrieves and removes the head of this queue, or {@code null} if empty. */
  @Override
  @SuppressWarnings("unchecked")
  public E poll() {
    final long currentHead = head;
    final int index = (int) (currentHead & mask);

    final E e = (E) buffer[index];
    if (e == null) {
      return null;
    }

    buffer[index] = null; // mark slot as free (safe since we only have 1 consumer and 1 producer)
    head = currentHead + 1; // volatile write to publish
    return e;
  }

  /**
   * Polls an element, waiting up to the given timeout.
   *
   * @param timeout maximum time to wait
   * @param unit time unit of the timeout
   * @return the next element or {@code null} if timed out
   * @throws InterruptedException if interrupted while waiting
   */
  public E poll(long timeout, TimeUnit unit) throws InterruptedException {
    if (timeout <= 0) {
      return poll();
    }

    final long deadline = System.nanoTime() + unit.toNanos(timeout);
    int idleCount = 0;

    for (; ; ) {
      E e = poll();
      if (e != null) {
        return e;
      }

      long remaining = deadline - System.nanoTime();
      if (remaining <= 0) {
        return null;
      }

      // Progressive backoff to reduce CPU usage
      if (idleCount < 100) {
        // light spin
      } else if (idleCount < 1_000) {
        Thread.yield();
      } else {
        LockSupport.parkNanos(Math.min(remaining, 1_000_000L)); // up to 1ms
      }
      idleCount++;

      if (Thread.interrupted()) {
        throw new InterruptedException();
      }
    }
  }

  /**
   * Retrieves, but does not remove, the head of this queue.
   *
   * @return the head element or {@code null} if empty
   */
  @Override
  @SuppressWarnings("unchecked")
  public E peek() {
    return (E) buffer[(int) (head & mask)];
  }

  /**
   * Returns an approximation of the number of elements in this queue. This value may be imprecise
   * due to concurrent updates.
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
   * Drains all available elements and passes them to the given consumer.
   *
   * @param consumer callback for each drained element
   * @return number of elements drained
   */
  public int drain(Consumer<E> consumer) {
    return drain(consumer, Integer.MAX_VALUE);
  }

  /**
   * Drains up to {@code limit} elements to the given consumer.
   *
   * @param consumer element consumer
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

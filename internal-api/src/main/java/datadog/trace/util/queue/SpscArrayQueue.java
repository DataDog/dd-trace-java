package datadog.trace.util.queue;

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
public final class SpscArrayQueue<E> extends BaseQueue<E> {
  private final Object[] buffer;

  // padding
  @SuppressWarnings("unused")
  private long p0, p1, p2, p3, p4, p5, p6;

  /** Producer index */
  private volatile long tail = 0L;

  // padding
  @SuppressWarnings("unused")
  private long q0, q1, q2, q3, q4, q5, q6;

  // padding
  @SuppressWarnings("unused")
  private long r0, r1, r2, r3, r4, r5, r6;

  /** Consumer index */
  private volatile long head = 0L;

  // padding
  @SuppressWarnings("unused")
  private long s0, s1, s2, s3, s4, s5, s6;

  /**
   * Constructs a new bounded Single producer single consumer queue.
   *
   * @param capacity the maximum number of elements the queue can hold. Will be rounded to the next
   *     power of two if not yet.
   */
  public SpscArrayQueue(int capacity) {
    super(capacity);
    this.buffer = new Object[this.capacity];
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
}

package datadog.trace.util.queue;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.VarHandle;
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
public class MpscArrayQueueVarHandle<E> extends BaseQueue<E> {
  private static final VarHandle ARRAY_HANDLE;
  private static final VarHandle HEAD_HANDLE;
  private static final VarHandle TAIL_HANDLE;
  private static final VarHandle PRODUCER_LIMIT_HANDLE;

  static {
    try {
      final Lookup lookup = MethodHandles.lookup();
      TAIL_HANDLE = lookup.findVarHandle(MpscArrayQueueVarHandle.class, "tail", long.class);
      HEAD_HANDLE = lookup.findVarHandle(MpscArrayQueueVarHandle.class, "head", long.class);
      ARRAY_HANDLE = MethodHandles.arrayElementVarHandle(Object[].class);
      PRODUCER_LIMIT_HANDLE =
          lookup.findVarHandle(MpscArrayQueueVarHandle.class, "producerLimit", long.class);
    } catch (Throwable t) {
      throw new IllegalStateException(t);
    }
  }

  /** The backing array (plain Java array for VarHandle access) */
  private final Object[] buffer;

  // Padding to prevent false sharing
  @SuppressWarnings("unused")
  private long p0, p1, p2, p3, p4, p5, p6;

  /** Next free slot for producers (multi-threaded) */
  private volatile long tail = 0L;

  // Padding around tail
  @SuppressWarnings("unused")
  private long q0, q1, q2, q3, q4, q5, q6;

  /** Cached producer limit to reduce volatile head reads */
  private volatile long producerLimit = 0L;

  // Padding around producerLimit
  @SuppressWarnings("unused")
  private long r0, r1, r2, r3, r4, r5, r6;

  /** Next slot to consume (single-threaded) */
  private volatile long head = 0L;

  // Padding around head
  @SuppressWarnings("unused")
  private long s0, s1, s2, s3, s4, s5, s6;

  /**
   * Creates a new MPSC queue.
   *
   * @param requestedCapacity the desired capacity, rounded up to next power of two
   */
  public MpscArrayQueueVarHandle(int requestedCapacity) {
    super(requestedCapacity);
    this.buffer = new Object[capacity];
    this.producerLimit = capacity;
  }

  /**
   * Attempts to add an element to the queue.
   *
   * @param e the element to add (must be non-null)
   * @return true if element was enqueued, false if queue is full
   */
  @Override
  public boolean offer(E e) {
    Objects.requireNonNull(e);

    // jctools does the same local copy to have the jitter optimise the accesses
    final Object[] localBuffer = this.buffer;

    // depending on the thread id, choose a different backoff strategy.
    // Note: it reduces fairness but also the contention on the cas.
    boolean s0 = false, s1 = false, s2 = false;
    switch ((int) (Thread.currentThread().getId() & 3)) {
      case 0:
        s0 = true;
        break;
      case 1:
        s1 = true;
        break;
      case 2:
        s2 = true;
        break;
      default:
        break;
    }

    long localProducerLimit = (long) PRODUCER_LIMIT_HANDLE.getVolatile(this);
    long cachedHead = 0L; // Local cache of head to reduce volatile reads

    while (true) {
      long currentTail = (long) TAIL_HANDLE.getVolatile(this);

      // Check if producer limit exceeded
      if (currentTail >= localProducerLimit) {
        // Refresh head only when necessary
        cachedHead = (long) HEAD_HANDLE.getVolatile(this);
        localProducerLimit = cachedHead + capacity;

        if (currentTail >= localProducerLimit) return false; // queue full

        // Update producerLimit so other producers also benefit
        PRODUCER_LIMIT_HANDLE.setVolatile(this, localProducerLimit);
      }

      // Attempt to claim a slot
      if (TAIL_HANDLE.compareAndSet(this, currentTail, currentTail + 1)) {
        final int index = (int) (currentTail & mask);

        // Release-store ensures producer's write is visible to consumer
        ARRAY_HANDLE.setRelease(localBuffer, index, e);
        return true;
      }

      // Backoff to reduce contention
      if (s0) Thread.onSpinWait();
      else if (s1) Thread.yield();
      else if (s2) LockSupport.parkNanos(1);
    }
  }

  /**
   * Removes and returns the next element, or null if empty.
   *
   * @return dequeued element, or null if queue empty
   */
  @Override
  @SuppressWarnings("unchecked")
  public E poll() {
    final Object[] localBuffer = this.buffer;

    long currentHead = (long) HEAD_HANDLE.getOpaque(this);
    final int index = (int) (currentHead & mask);

    // Acquire-load ensures visibility of producer write
    Object value = ARRAY_HANDLE.getAcquire(localBuffer, index);
    if (value == null) return null;

    // Clear the slot without additional fence
    ARRAY_HANDLE.setOpaque(localBuffer, index, null);

    // Advance head using opaque write (consumer-only)
    HEAD_HANDLE.setOpaque(this, currentHead + 1);

    return (E) value;
  }

  /**
   * Returns next element without removing it.
   *
   * <p>The memory visibility is only correct if the consumer calls it.
   *
   * @return next element or null if empty
   */
  @Override
  @SuppressWarnings("unchecked")
  public E peek() {
    final int index = (int) ((long) HEAD_HANDLE.getOpaque(this) & mask);
    return (E) ARRAY_HANDLE.getVolatile(buffer, index);
  }

  /**
   * Returns number of elements in queue.
   *
   * <p>Volatile reads of tail and head ensure accurate result in multi-threaded context.
   *
   * @return current size
   */
  @Override
  public int size() {
    long currentHead = (long) HEAD_HANDLE.getVolatile(this);
    long currentTail = (long) TAIL_HANDLE.getVolatile(this);
    return (int) (currentTail - currentHead);
  }
}

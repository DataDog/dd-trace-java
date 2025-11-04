package datadog.trace.util.queue;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * A high-performance Single-Producer, Single-Consumer (SPSC) bounded queue using a circular buffer
 * and VarHandle-based release/acquire memory semantics.
 *
 * <p>It is completely lock-free and wait-free, relying solely on release/acquire ordering for
 * correctness and visibility.
 *
 * @param <E> the type of elements held in this queue
 */
public class SpscArrayQueueVarHandle<E> extends BaseQueue<E> {
  /** Backing array storing elements. */
  private final Object[] buffer;

  // ===================== Padding to avoid false sharing =====================

  @SuppressWarnings("unused")
  private long p0, p1, p2, p3, p4, p5, p6;

  /** Tail index (producer writes). */
  private volatile long tail = 0L;

  @SuppressWarnings("unused")
  private long q0, q1, q2, q3, q4, q5, q6;
  @SuppressWarnings("unused")
  private long p10, p11, p12, p13, p14, p15, p16;

  /** Head index (consumer writes). */
  private volatile long head = 0L;

  @SuppressWarnings("unused")
  private long q10, q11, q12, q13, q14, q15, q16;

  private static final VarHandle HEAD_HANDLE;
  private static final VarHandle TAIL_HANDLE;
  private static final VarHandle ARRAY_HANDLE;

  static {
    try {
      final MethodHandles.Lookup lookup = MethodHandles.lookup();
      HEAD_HANDLE = lookup.findVarHandle(SpscArrayQueueVarHandle.class, "head", long.class);
      TAIL_HANDLE = lookup.findVarHandle(SpscArrayQueueVarHandle.class, "tail", long.class);
      ARRAY_HANDLE = MethodHandles.arrayElementVarHandle(Object[].class);
    } catch (Throwable t) {
      throw new IllegalStateException(t);
    }
  }

  /**
   * Creates a new SPSC queue with the specified capacity. Capacity must be a power of two.
   *
   * @param requestedCapacity the desired capacity, rounded up to the next power of two if needed
   */
  public SpscArrayQueueVarHandle(int requestedCapacity) {
    super(requestedCapacity);
    this.buffer = new Object[capacity];
  }

  // ===================== OFFER (Producer only) =====================

  /**
   * Enqueues an element if space is available.
   *
   * <p>Since only one producer exists, this method uses simple volatile semantics and never
   * contends or retries.
   *
   * @param e the element to enqueue
   * @return {@code true} if enqueued, {@code false} if the queue is full
   * @throws NullPointerException if {@code e} is null
   */
  @Override
  public boolean offer(E e) {
    if (e == null) {
      throw new NullPointerException();
    }

    long currentTail = (long) TAIL_HANDLE.getVolatile(this);
    int index = (int) (currentTail & mask);

    // Check if the next slot is still occupied
    Object existing = ARRAY_HANDLE.getVolatile(buffer, index);
    if (existing != null) {
      return false; // queue full
    }

    // Publish element (release semantics)
    ARRAY_HANDLE.setRelease(buffer, index, e);

    // Advance tail (release ensures enqueue visibility)
    TAIL_HANDLE.setRelease(this, currentTail + 1);
    return true;
  }

  /**
   * Dequeues and returns the next element, or {@code null} if the queue is empty.
   *
   * <p>Since only one consumer exists, this method is race-free and does not need CAS. It uses
   * acquire semantics to ensure the element is fully visible.
   *
   * @return the dequeued element, or {@code null} if empty
   */
  @Override
  @SuppressWarnings("unchecked")
  public E poll() {
    long currentHead = (long) HEAD_HANDLE.getVolatile(this);
    int index = (int) (currentHead & mask);

    Object value = ARRAY_HANDLE.getAcquire(buffer, index);
    if (value == null) {
      return null; // queue empty
    }

    // Clear slot (release to make it visible to producer)
    ARRAY_HANDLE.setRelease(buffer, index, null);

    // Advance head (release to ensure ordering)
    HEAD_HANDLE.setRelease(this, currentHead + 1);

    return (E) value;
  }

  @Override
  @SuppressWarnings("unchecked")
  public E peek() {
    int index = (int) ((long) HEAD_HANDLE.getVolatile(this) & mask);
    return (E) ARRAY_HANDLE.getVolatile(buffer, index);
  }

  @Override
  public int size() {
    long currentTail = (long) TAIL_HANDLE.getVolatile(this);
    long currentHead = (long) HEAD_HANDLE.getVolatile(this);
    return (int) (currentTail - currentHead);
  }
}

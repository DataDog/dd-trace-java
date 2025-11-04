package datadog.trace.util.queue;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.VarHandle;
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

  static {
    try {
      final Lookup lookup = MethodHandles.lookup();
      TAIL_HANDLE = lookup.findVarHandle(MpscArrayQueueVarHandle.class, "tail", long.class);
      HEAD_HANDLE = lookup.findVarHandle(MpscArrayQueueVarHandle.class, "head", long.class);
      ARRAY_HANDLE = MethodHandles.arrayElementVarHandle(Object[].class);
    } catch (Throwable t) {
      throw new IllegalStateException(t);
    }
  }

  /** The backing array (plain Java array for VarHandle access) */
  private final Object[] buffer;

  // Padding
  @SuppressWarnings("unused")
  private long p0, p1, p2, p3, p4, p5, p6;

  /** The next free slot for producers */
  private volatile long tail = 0L;

  // Padding
  @SuppressWarnings("unused")
  private long q0, q1, q2, q3, q4, q5, q6;

  // Padding
  @SuppressWarnings("unused")
  private long p10, p11, p12, p13, p14, p15, p16;

  /** The next slot to consume (single-threaded) */
  private volatile long head = 0L;

  // Padding
  private long q10, q11, q12, q13, q14, q15, q16;

  /**
   * Creates a new MPSC queue.
   *
   * @param requestedCapacity the desired capacity, rounded up to the next power of two if needed
   */
  public MpscArrayQueueVarHandle(int requestedCapacity) {
    super(requestedCapacity);
    this.buffer = new Object[capacity];
  }

  /**
   * Attempts to add an element to the queue.
   *
   * <p>This method uses a CAS loop on {@code tail} to allow multiple producers to safely claim
   * distinct slots. The producer then performs a release-store into the buffer using {@code
   * ARRAY_HANDLE.setRelease()}.
   *
   * @param e the element to add (must be non-null)
   * @return {@code true} if the element was enqueued, {@code false} if the queue is full
   */
  @Override
  public boolean offer(E e) {
    if (e == null) {
      throw new NullPointerException();
    }

    while (true) {
      final long currentTail = (long) TAIL_HANDLE.getVolatile(this);
      final long wrapPoint = currentTail - capacity;
      final long currentHead = (long) HEAD_HANDLE.getVolatile(this);

      if (wrapPoint >= currentHead) {
        return false; // full
      }

      if (TAIL_HANDLE.compareAndSet(this, currentTail, currentTail + 1)) {
        final int index = (int) (currentTail & mask);
        ARRAY_HANDLE.setRelease(buffer, index, e);
        return true;
      }

      // Backoff on contention
      LockSupport.parkNanos(1L);
    }
  }

  /**
   * Removes and returns the next element, or {@code null} if the queue is empty.
   *
   * <p>This method is single-threaded (one consumer). It performs a volatile read of the buffer,
   * and then uses {@code setRelease(null)} to free the slot.
   *
   * @return the dequeued element, or null if the queue is empty
   */
  @Override
  @SuppressWarnings("unchecked")
  public E poll() {
    final long currentHead = (long) HEAD_HANDLE.getOpaque(this);
    final int index = (int) (currentHead & mask);

    Object value = ARRAY_HANDLE.getAcquire(buffer, index);
    if (value == null) {
      return null;
    }

    ARRAY_HANDLE.setOpaque(buffer, index, null); // clear slot
    HEAD_HANDLE.setOpaque(this, currentHead + 1);
    return (E) value;
  }

  @Override
  @SuppressWarnings("unchecked")
  public E peek() {
    final int index = (int) ((long) HEAD_HANDLE.getOpaque(this) & mask);
    return (E) ARRAY_HANDLE.getVolatile(buffer, index);
  }

  @Override
  public int size() {
    long currentHead = head; // non-volatile read
    long currentTail = (long) TAIL_HANDLE.getVolatile(this);
    return (int) (currentTail - currentHead);
  }
}

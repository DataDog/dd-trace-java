package datadog.trace.util.queue;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * A Single-Producer, Multiple-Consumer (SPMC) bounded, lock-free queue based on a circular array.
 *
 * <p>All operations are wait-free for the single producer and lock-free for consumers.
 *
 * @param <E> the element type
 */
public class SpmcArrayQueueVarHandle<E> extends BaseQueue<E> {

  private static final VarHandle HEAD_HANDLE;
  private static final VarHandle TAIL_HANDLE;
  private static final VarHandle ARRAY_HANDLE;

  static {
    try {
      final MethodHandles.Lookup lookup = MethodHandles.lookup();
      HEAD_HANDLE = lookup.findVarHandle(SpmcArrayQueueVarHandle.class, "head", long.class);
      TAIL_HANDLE = lookup.findVarHandle(SpmcArrayQueueVarHandle.class, "tail", long.class);
      ARRAY_HANDLE = MethodHandles.arrayElementVarHandle(Object[].class);
    } catch (ReflectiveOperationException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /** Backing array buffer. */
  private final Object[] buffer;

  // Padding
  @SuppressWarnings("unused")
  private long p0, p1, p2, p3, p4, p5, p6;

  /** Tail index (producer-only). */
  private volatile long tail = 0L;

  // Padding
  @SuppressWarnings("unused")
  private long q0, q1, q2, q3, q4, q5, q6;

  // Padding
  @SuppressWarnings("unused")
  private long p10, p11, p12, p13, p14, p15, p16;

  /** Head index (claimed atomically by multiple consumers). */
  private volatile long head = 0L;

  // Padding
  @SuppressWarnings("unused")
  private long q10, q11, q12, q13, q14, q15, q16;

  /**
   * Creates a new SPMC queue with the given capacity.
   *
   * @param requestedCapacity the desired capacity, rounded up to the next power of two if needed
   */
  public SpmcArrayQueueVarHandle(int requestedCapacity) {
    super(requestedCapacity);
    this.buffer = new Object[capacity];
  }

  /**
   * Attempts to enqueue the given element.
   *
   * <p>This method is called by a single producer, so no CAS is required. It uses {@code
   * setRelease} to publish the element and the new tail value.
   *
   * @param e the element to add
   * @return {@code true} if added, {@code false} if the queue is full
   * @throws NullPointerException if {@code e} is null
   */
  @Override
  public boolean offer(E e) {
    if (e == null) {
      throw new NullPointerException();
    }

    long currentTail = tail;
    long wrapPoint = currentTail - capacity;
    long currentHead = (long) HEAD_HANDLE.getVolatile(this);
    if (wrapPoint >= currentHead) {
      return false; // queue full
    }

    int index = (int) (currentTail & mask);
    ARRAY_HANDLE.setRelease(buffer, index, e);
    tail = currentTail + 1;
    return true;
  }

  /**
   * Removes and returns the next element, or {@code null} if the queue is empty.
   *
   * <p>Consumers compete via CAS on {@code head}. The successful thread claims the index and clears
   * the slot with release semantics.
   *
   * @return the dequeued element, or {@code null} if empty
   */
  @Override
  @SuppressWarnings("unchecked")
  public E poll() {
    while (true) {
      long currentHead = (long) HEAD_HANDLE.getVolatile(this);
      int index = (int) (currentHead & mask);

      Object value = ARRAY_HANDLE.getAcquire(buffer, index);
      if (value == null) {
        // Possibly empty
        long currentTail = tail; // producer thread only writes
        if (currentHead >= currentTail) {
          return null; // queue empty
        } else {
          // Not yet visible, retry
          Thread.onSpinWait();
          continue;
        }
      }

      // Try to claim this slot
      if (HEAD_HANDLE.compareAndSet(this, currentHead, currentHead + 1)) {
        ARRAY_HANDLE.setOpaque(buffer, index, null);
        return (E) value;
      }

      // Lost race to another consumer
      Thread.onSpinWait();
    }
  }

  @Override
  @SuppressWarnings("unchecked")
  public E peek() {
    long currentHead = (long) HEAD_HANDLE.getVolatile(this);
    int index = (int) (currentHead & mask);
    return (E) ARRAY_HANDLE.getVolatile(buffer, index);
  }

  @Override
  public int size() {
    long currentHead = (long) HEAD_HANDLE.getVolatile(this);
    long currentTail = tail;
    return (int) (currentTail - currentHead);
  }
}

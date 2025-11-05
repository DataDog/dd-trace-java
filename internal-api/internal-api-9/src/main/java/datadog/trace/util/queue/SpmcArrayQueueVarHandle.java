package datadog.trace.util.queue;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Objects;

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

  /** The backing array (plain Java array for VarHandle access) */
  private final Object[] buffer;

  // Padding to avoid false sharing
  @SuppressWarnings("unused")
  private long p0, p1, p2, p3, p4, p5, p6;

  /** Next free slot for producer (single-threaded) */
  private volatile long tail = 0L;

  // Padding around tail
  @SuppressWarnings("unused")
  private long q0, q1, q2, q3, q4, q5, q6;

  /** Next slot to consume (multi-threaded) */
  private volatile long head = 0L;

  /** Cached consumer limit to avoid repeated volatile tail reads */
  private volatile long consumerLimit = 0L;

  // Padding around head
  @SuppressWarnings("unused")
  private long r0, r1, r2, r3, r4, r5, r6;

  /**
   * Creates a new SPMC queue.
   *
   * @param requestedCapacity the desired capacity, rounded up to next power of two
   */
  public SpmcArrayQueueVarHandle(int requestedCapacity) {
    super(requestedCapacity);
    this.buffer = new Object[capacity];
  }

  /**
   * Adds an element to the queue.
   *
   * <p>Single-producer: no CAS needed. Uses a release-store to ensure consumers see the write.
   *
   * @param e element to enqueue (must be non-null)
   * @return true if element was added, false if queue is full
   */
  @Override
  public boolean offer(E e) {
    Objects.requireNonNull(e);

    long currentTail = tail;
    long wrapPoint = currentTail - capacity;
    long currentHead = (long) HEAD_HANDLE.getVolatile(this);

    if (wrapPoint >= currentHead) return false; // queue full

    int index = (int) (currentTail & mask);

    // Release-store ensures that the element is visible to consumers
    ARRAY_HANDLE.setRelease(this.buffer, index, e);

    // Single-producer: simple volatile write to advance tail
    TAIL_HANDLE.setVolatile(this, currentTail + 1);
    return true;
  }

  /**
   * Removes and returns the next element, or null if empty.
   *
   * @return dequeued element, or null if queue is empty
   */
  @Override
  @SuppressWarnings("unchecked")
  public E poll() {
    final Object[] localBuffer = this.buffer;

    while (true) {
      long currentHead = (long) HEAD_HANDLE.getVolatile(this);
      long limit = consumerLimit; // local cached tail

      if (currentHead >= limit) {
        limit = (long) TAIL_HANDLE.getVolatile(this);
        if (currentHead >= limit) {
          return null; // empty
        }
        consumerLimit = limit; // update local view
      }

      // Attempt to claim this slot
      if (HEAD_HANDLE.compareAndSet(this, currentHead, currentHead + 1)) {
        int index = (int) (currentHead & mask);
        Object value;

        // Wait for the producer to publish the value
        while ((value = ARRAY_HANDLE.getAcquire(localBuffer, index)) == null) {
          Thread.onSpinWait();
        }

        // Clear slot
        ARRAY_HANDLE.setOpaque(localBuffer, index, null);
        return (E) value;
      }

      // CAS failed, retry loop
    }
  }

  /**
   * Returns the next element without removing it.
   *
   * @return next element or null if queue empty
   */
  @Override
  @SuppressWarnings("unchecked")
  public E peek() {
    final Object[] localBuffer = this.buffer;
    long currentHead = (long) HEAD_HANDLE.getVolatile(this);
    long currentTail = (long) TAIL_HANDLE.getVolatile(this);

    if (currentHead >= currentTail) return null;

    int index = (int) (currentHead & mask);
    return (E) ARRAY_HANDLE.getAcquire(localBuffer, index); // acquire-load ensures visibility
  }

  /**
   * Returns the approximate number of elements in the queue.
   *
   * @return current queue size
   */
  @Override
  public int size() {
    long currentTail = (long) TAIL_HANDLE.getVolatile(this);
    long currentHead = (long) HEAD_HANDLE.getVolatile(this);
    return (int) (currentTail - currentHead);
  }
}

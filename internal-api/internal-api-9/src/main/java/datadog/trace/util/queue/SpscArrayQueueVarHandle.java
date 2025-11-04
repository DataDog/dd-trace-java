package datadog.trace.util.queue;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;

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

  // These caches eliminate redundant volatile reads
  private long cachedHead = 0L; // visible only to producer
  private long cachedTail = 0L; // visible only to consumer

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
   * <p>Uses cached head to minimize volatile reads. Only refreshes the head when the queue looks
   * full. Writes use release semantics for publication.
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

    final long currentTail = (long) TAIL_HANDLE.getOpaque(this);
    final int index = (int) (currentTail & mask);

    if (currentTail - cachedHead >= capacity) {
      // Refresh cached head (read from consumer side)
      cachedHead = (long) HEAD_HANDLE.getVolatile(this);
      if (currentTail - cachedHead >= capacity) {
        return false; // still full
      }
    }

    ARRAY_HANDLE.setRelease(buffer, index, e); // publish value
    TAIL_HANDLE.setOpaque(this, currentTail + 1); // relaxed tail update
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
    final long currentHead = (long) HEAD_HANDLE.getOpaque(this);
    final int index = (int) (currentHead & mask);

    if (currentHead >= cachedTail) {
      // refresh tail cache
      cachedTail = (long) TAIL_HANDLE.getVolatile(this);
      if (currentHead >= cachedTail) {
        return null; // still empty
      }
    }

    Object value = ARRAY_HANDLE.getAcquire(buffer, index);
    ARRAY_HANDLE.setOpaque(buffer, index, null); // clear slot
    HEAD_HANDLE.setOpaque(this, currentHead + 1); // relaxed head update
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
    long currentTail = (long) TAIL_HANDLE.getVolatile(this);
    long currentHead = (long) HEAD_HANDLE.getVolatile(this);
    return (int) (currentTail - currentHead);
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
  @Override
  public boolean offer(E e, long timeout, @Nonnull TimeUnit unit) throws InterruptedException {
    long deadline = System.nanoTime() + unit.toNanos(timeout);
    int idle = 0;

    while (true) {
      if (offer(e)) return true;

      long remaining = deadline - System.nanoTime();
      if (remaining <= 0) return false;

      // progressive spin/yield
      if (idle < 100) {
        // spin
      } else if (idle < 1_000) {
        Thread.yield();
      } else {
        Thread.onSpinWait();
      }
      if (Thread.interrupted()) {
        throw new InterruptedException();
      }
      idle++;
    }
  }

  /**
   * Polls with a timeout using progressive backoff.
   *
   * @param timeout max wait time
   * @param unit time unit
   * @return the head element, or null if timed out
   * @throws InterruptedException if interrupted
   */
  @Override
  public E poll(long timeout, @Nonnull TimeUnit unit) throws InterruptedException {
    if (timeout <= 0) {
      return poll();
    }

    final long deadline = System.nanoTime() + unit.toNanos(timeout);
    int idleCount = 0;

    while (true) {
      E e = poll();
      if (e != null) {
        return e;
      }

      long remaining = deadline - System.nanoTime();
      if (remaining <= 0) {
        return null;
      }

      if (idleCount < 100) {
        // spin
      } else if (idleCount < 1_000) {
        Thread.yield();
      } else {
        Thread.onSpinWait();
      }
      if (Thread.interrupted()) {
        throw new InterruptedException();
      }
      idleCount++;
    }
  }
}

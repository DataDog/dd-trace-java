package datadog.common.queue;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.VarHandle;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import javax.annotation.Nonnull;

/**
 * A Multiple-Producer, Single-Consumer (MPSC) bounded lock-free queue using a circular array and
 * VarHandles. It adds blocking capabilities for a single consumer (take, timed offer).
 *
 * <p>All operations are wait-free for the consumer and lock-free for producers.
 *
 * @param <E> the type of elements stored
 */
class MpscBlockingConsumerArrayQueueVarHandle<E> extends BaseQueue<E>
    implements BlockingConsumerNonBlockingQueue<E> {
  private static final VarHandle ARRAY_HANDLE;
  private static final VarHandle HEAD_HANDLE;
  private static final VarHandle TAIL_HANDLE;
  private static final VarHandle PRODUCER_LIMIT_HANDLE;
  private static final VarHandle CONSUMER_THREAD_HANDLE;

  static {
    try {
      final Lookup lookup = MethodHandles.lookup();
      TAIL_HANDLE =
          lookup.findVarHandle(MpscBlockingConsumerArrayQueueVarHandle.class, "tail", long.class);
      HEAD_HANDLE =
          lookup.findVarHandle(MpscBlockingConsumerArrayQueueVarHandle.class, "head", long.class);
      ARRAY_HANDLE = MethodHandles.arrayElementVarHandle(Object[].class);
      PRODUCER_LIMIT_HANDLE =
          lookup.findVarHandle(
              MpscBlockingConsumerArrayQueueVarHandle.class, "producerLimit", long.class);
      CONSUMER_THREAD_HANDLE =
          lookup.findVarHandle(
              MpscBlockingConsumerArrayQueueVarHandle.class, "consumerThread", Thread.class);
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

  /** Reference to the waiting consumer thread (set atomically). */
  private volatile Thread consumerThread;

  /**
   * Creates a new MPSC queue.
   *
   * @param requestedCapacity the desired capacity, rounded up to next power of two
   */
  public MpscBlockingConsumerArrayQueueVarHandle(int requestedCapacity) {
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

    long localProducerLimit = (long) PRODUCER_LIMIT_HANDLE.getVolatile(this);
    long cachedHead = 0L; // Local cache of head to reduce volatile reads

    int spinCycles = 0;
    boolean parkOnSpin = (Thread.currentThread().getId() & 1) == 0;

    while (true) {
      long currentTail = (long) TAIL_HANDLE.getVolatile(this);

      // Check if producer limit exceeded
      if (currentTail >= localProducerLimit) {
        // Refresh head only when necessary
        cachedHead = (long) HEAD_HANDLE.getVolatile(this);
        localProducerLimit = cachedHead + capacity;

        if (currentTail >= localProducerLimit) {
          return false; // queue full
        }

        // Update producerLimit so other producers also benefit
        PRODUCER_LIMIT_HANDLE.setVolatile(this, localProducerLimit);
      }

      // Attempt to claim a slot
      if (TAIL_HANDLE.compareAndSet(this, currentTail, currentTail + 1)) {
        final int index = (int) (currentTail & mask);

        // Release-store ensures producer's write is visible to consumer
        ARRAY_HANDLE.setRelease(localBuffer, index, e);

        // Atomically clear and unpark the consumer if waiting
        Thread c = (Thread) CONSUMER_THREAD_HANDLE.getAndSet(this, null);
        if (c != null) {
          LockSupport.unpark(c);
        }

        return true;
      }

      // Backoff to reduce contention
      if ((spinCycles & 1) == 0) {
        Thread.onSpinWait();
      } else {
        if (parkOnSpin) {
          LockSupport.parkNanos(1);
        } else {
          Thread.yield();
        }
      }
      spinCycles++;
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
    if (value == null) {
      return null;
    }

    // Clear the slot without additional fence
    ARRAY_HANDLE.setOpaque(localBuffer, index, null);

    // Advance head using opaque write (consumer-only)
    HEAD_HANDLE.setOpaque(this, currentHead + 1);

    return (E) value;
  }

  /**
   * Polls with a timeout.
   *
   * @param timeout max wait time
   * @param unit time unit
   * @return the head element, or null if timed out
   * @throws InterruptedException if interrupted
   */
  @Override
  public E poll(long timeout, @Nonnull TimeUnit unit) throws InterruptedException {
    E e = poll();
    if (e != null) {
      return e;
    }

    final long parkNanos = unit.toNanos(timeout);
    if (parkNanos <= 0) {
      return null;
    }

    parkUntilNext(parkNanos);

    return poll();
  }

  /**
   * Retrieves and removes the head element, waiting if necessary until one becomes available.
   *
   * @return the next element (never null)
   * @throws InterruptedException if interrupted while waiting
   */
  @Override
  public E take() throws InterruptedException {
    consumerThread = Thread.currentThread();
    E e;
    while ((e = poll()) == null) {
      parkUntilNext(-1);
    }
    return e;
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

  /**
   * Blocks (parks) until an element becomes available or until the specified timeout elapses.
   *
   * <p>It is safe if only one thread is waiting (it's the case for this single consumer
   * implementation).
   *
   * @param nanos max wait time in nanoseconds. If negative, it will park indefinably until waken or
   *     interrupted
   * @throws InterruptedException if interrupted
   */
  private void parkUntilNext(long nanos) throws InterruptedException {
    Thread current = Thread.currentThread();
    // Publish the consumer thread (no ordering required)
    CONSUMER_THREAD_HANDLE.setOpaque(this, current);
    if (nanos <= 0) {
      LockSupport.park(this);
    } else {
      LockSupport.parkNanos(this, nanos);
    }

    if (Thread.interrupted()) {
      throw new InterruptedException();
    }

    // Cleanup (no fence needed, single consumer)
    CONSUMER_THREAD_HANDLE.setOpaque(this, null);
  }
}

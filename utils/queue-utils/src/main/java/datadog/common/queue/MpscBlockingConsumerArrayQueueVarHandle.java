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
class MpscBlockingConsumerArrayQueueVarHandle<E> extends MpscArrayQueueVarHandle<E>
    implements BlockingConsumerNonBlockingQueue<E> {
  private static final VarHandle CONSUMER_THREAD_HANDLE;

  static {
    try {
      final Lookup lookup = MethodHandles.lookup();
      CONSUMER_THREAD_HANDLE =
          lookup.findVarHandle(
              MpscBlockingConsumerArrayQueueVarHandle.class, "consumerThread", Thread.class);
    } catch (Throwable t) {
      throw new ExceptionInInitializerError(t);
    }
  }

  // Padding to prevent false sharing
  @SuppressWarnings("unused")
  private long p0, p1, p2, p3, p4, p5, p6;

  /** Reference to the waiting consumer thread (set atomically). */
  private volatile Thread consumerThread;

  // Padding around consumerThread
  @SuppressWarnings("unused")
  private long q0, q1, q2, q3, q4, q5, q6;

  /**
   * Creates a new MPSC queue.
   *
   * @param requestedCapacity the desired capacity, rounded up to next power of two
   */
  public MpscBlockingConsumerArrayQueueVarHandle(int requestedCapacity) {
    super(requestedCapacity);
  }

  @Override
  public final boolean offer(E e) {
    Objects.requireNonNull(e);

    // jctools does the same local copy to have the jitter optimise the accesses
    final Object[] localBuffer = this.buffer;

    long localProducerLimit = producerLimit;
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
        producerLimit = localProducerLimit;
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

  @Override
  public final E poll(long timeout, @Nonnull TimeUnit unit) throws InterruptedException {
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
   * Blocks (parks) until an element becomes available or until the specified timeout elapses.
   *
   * <p>It is safe if only one thread is waiting (it's the case for this single consumer
   * implementation).
   *
   * @param nanos max wait time in nanoseconds. If negative, it will park indefinably until waken or
   *     interrupted
   * @throws InterruptedException if interrupted while waiting
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

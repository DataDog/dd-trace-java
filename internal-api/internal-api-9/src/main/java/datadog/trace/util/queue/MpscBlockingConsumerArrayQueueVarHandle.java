package datadog.trace.util.queue;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import javax.annotation.Nonnull;

/**
 * A MPSC Array queue offering blocking methods (take and timed poll) for a single consumer.
 *
 * <p>The wait is performed by parking/unparking the consumer thread.
 */
public class MpscBlockingConsumerArrayQueueVarHandle<E> extends MpscArrayQueueVarHandle<E>
    implements BlockingConsumerNonBlockingQueue<E> {
  /** Consumer thread reference for wake-up. */
  private volatile Thread consumerThread;

  public MpscBlockingConsumerArrayQueueVarHandle(int capacity) {
    super(capacity);
  }

  @Override
  public boolean offer(E e) {
    final boolean success = super.offer(e);
    if (success) {
      try {
        final Thread c = consumerThread;
        LockSupport.unpark(c); // unpark is safe if the arg is null
      } finally {
        consumerThread = null;
      }
    }

    return success;
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
    while ((e = poll()) != null) {
      parkUntilNext(-1);
    }
    return e;
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
    try {
      // register this thread as the waiting consumer
      consumerThread = Thread.currentThread();
      if (nanos <= 0) {
        LockSupport.park(this);
      } else {
        LockSupport.parkNanos(this, nanos);
      }
      if (Thread.interrupted()) {
        throw new InterruptedException();
      }
    } finally {
      // free the variable not to reference the consumer thread anymore
      consumerThread = null;
    }
  }
}

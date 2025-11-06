package datadog.trace.util.queue;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import javax.annotation.Nonnull;

/**
 * JCtools-like MpscBlockingConsumerArrayQueue implemented without Unsafe.
 *
 * <p>It features nonblocking offer/poll methods and blocking (condition based) take/put.
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
      Thread c = consumerThread;
      if (c != null) LockSupport.unpark(c);
    }
    return success;
  }

  public void put(E e) throws InterruptedException {
    // in this variant we should not use a blocking put since we do not support blocking producers
    throw new UnsupportedOperationException();
  }

  /**
   * Retrieves and removes the head element, waiting if necessary until one becomes available.
   *
   * @return the next element (never null)
   * @throws InterruptedException if interrupted while waiting
   */
  public E take() throws InterruptedException {
    consumerThread = Thread.currentThread();
    while (true) {
      E e = poll();
      if (e != null) return e;

      if (Thread.interrupted()) {
        throw new InterruptedException();
      }
      // Block until producer unparks us
      LockSupport.park(this);
    }
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
  public boolean offer(E e, long timeout, @Nonnull TimeUnit unit) throws InterruptedException {
    final long deadline = System.nanoTime() + unit.toNanos(timeout);
    int idleCount = 0;

    while (true) {
      if (offer(e)) {
        return true; // successfully inserted
      }

      long remaining = deadline - System.nanoTime();
      if (remaining <= 0) {
        return false; // timeout
      }

      // Progressive backoff
      if (idleCount < 100) {
        // spin (busy-wait)
      } else if (idleCount < 1_000) {
        Thread.yield(); // give up CPU to other threads
      } else {
        // park for a short duration, up to 1 ms
        LockSupport.parkNanos(Math.min(remaining, 1_000_000L));
      }

      idleCount++;

      if (Thread.interrupted()) {
        throw new InterruptedException();
      }
    }
  }

  /**
   * Polls with a timeout.
   *
   * @param timeout max wait time
   * @param unit time unit
   * @return the head element, or null if timed out
   * @throws InterruptedException if interrupted
   */
  public E poll(long timeout, @Nonnull TimeUnit unit) throws InterruptedException {
    final long deadline = System.nanoTime() + unit.toNanos(timeout);

    E e = poll();
    if (e != null) {
      return e;
    }

    // register this thread as the waiting consumer
    consumerThread = Thread.currentThread();
    final long remaining = deadline - System.nanoTime();

    if (remaining <= 0) {
      consumerThread = null;
      return null;
    }

    LockSupport.parkNanos(this, remaining);
    if (Thread.interrupted()) {
      throw new InterruptedException();
    }
    return poll();
  }
}

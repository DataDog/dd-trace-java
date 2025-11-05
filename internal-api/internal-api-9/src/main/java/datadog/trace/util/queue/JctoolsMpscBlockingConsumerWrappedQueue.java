package datadog.trace.util.queue;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import javax.annotation.Nonnull;
import org.jctools.queues.MpscBlockingConsumerArrayQueue;

public class JctoolsMpscBlockingConsumerWrappedQueue<E> extends JctoolsWrappedQueue<E>
    implements BlockingConsumerNonBlockingQueue<E> {

  private final BlockingQueue<E> blockingQueueDelegate;

  public JctoolsMpscBlockingConsumerWrappedQueue(
      @Nonnull MpscBlockingConsumerArrayQueue<E> delegate) {
    super(delegate);
    this.blockingQueueDelegate = delegate;
  }

  @Override
  public void put(E e) throws InterruptedException {
    blockingQueueDelegate.put(e);
  }

  @Override
  public E take() throws InterruptedException {
    return blockingQueueDelegate.take();
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
   * Polls with a timeout using progressive backoff.
   *
   * @param timeout max wait time
   * @param unit time unit
   * @return the head element, or null if timed out
   * @throws InterruptedException if interrupted
   */
  @Override
  public E poll(long timeout, @Nonnull TimeUnit unit) throws InterruptedException {
    final long deadline = System.nanoTime() + unit.toNanos(timeout);
    int idleCount = 0;

    while (true) {
      E value = poll();
      if (value != null) {
        return value;
      }

      long remaining = deadline - System.nanoTime();
      if (remaining <= 0) {
        return null;
      }

      // Progressive backoff
      if (idleCount < 100) {
        // spin
      } else if (idleCount < 1_000) {
        Thread.yield();
      } else {
        LockSupport.parkNanos(Math.min(remaining, 1_000_000L));
      }
      idleCount++;

      if (Thread.interrupted()) {
        throw new InterruptedException();
      }
    }
  }
}

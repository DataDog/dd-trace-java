package datadog.common.queue;

import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;

/**
 * A hybrid queue interface combining non-blocking producer semantics with blocking consumer
 * operations.
 *
 * <p>This interface extends {@link NonBlockingQueue} and adds methods that allow consumers to block
 * while waiting for elements to become available. It is intended for use in scenarios with:
 *
 * <ul>
 *   <li>Multiple or single <b>producers</b> enqueue elements using non-blocking operations (e.g.,
 *       {@link #offer(Object)}).
 *   <li>A single <b>consumer</b> that may block until elements are ready (i.e., using {@link
 *       #take()} or {@link #poll(long, TimeUnit)}).
 * </ul>
 *
 * @param <E> the type of elements held in this queue
 */
public interface BlockingConsumerNonBlockingQueue<E> extends NonBlockingQueue<E> {

  /**
   * Retrieves and removes the head of this queue, waiting up to the specified wait time if
   * necessary for an element to become available.
   *
   * @param timeout how long to wait before giving up, in units of {@code unit}
   * @param unit the time unit of the {@code timeout} argument; must not be {@code null}
   * @return the head of this queue, or {@code null} if the specified waiting time elapses before an
   *     element becomes available
   * @throws InterruptedException if interrupted while waiting
   */
  E poll(long timeout, @Nonnull TimeUnit unit) throws InterruptedException;

  /**
   * Retrieves and removes the head of this queue, waiting if necessary until an element becomes
   * available.
   *
   * <p>This operation blocks the consumer thread if the queue is empty, while producers continue to
   * operate in a non-blocking manner.
   *
   * @return the head of this queue
   * @throws InterruptedException if interrupted while waiting
   */
  E take() throws InterruptedException;
}

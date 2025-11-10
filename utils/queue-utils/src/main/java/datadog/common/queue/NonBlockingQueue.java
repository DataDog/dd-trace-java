package datadog.common.queue;

import java.util.Queue;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.annotation.Nonnull;

/**
 * A non-blocking, concurrent queue supporting high-performance operations for producer-consumer
 * scenarios. This interface extends {@link Queue} and adds specialized methods for bulk draining
 * and filling, as well as querying the queue’s fixed capacity.
 *
 * <p>Unlike typical {@link java.util.concurrent.BlockingQueue} implementations, this interface does
 * not provide blocking operations. Instead, producers and consumers are expected to retry or yield
 * when the queue is full or empty, respectively.
 *
 * <p>Implementations are typically array-backed and rely on non-blocking atomic operations (such as
 * VarHandles or Unsafe-based CAS) to achieve concurrent performance without locks.
 *
 * @param <E> the type of elements held in this queue
 * @see java.util.Queue
 * @see java.util.concurrent.ConcurrentLinkedQueue
 */
public interface NonBlockingQueue<E> extends Queue<E> {

  /**
   * Drains all available elements from this queue, passing each to the given {@link Consumer}.
   *
   * <p>This method will consume as many elements as are currently available, up to the queue’s size
   * at the time of the call.
   *
   * @param consumer the consumer that will process each element; must not be {@code null}
   * @return the number of elements drained
   * @throws NullPointerException if {@code consumer} is {@code null}
   */
  int drain(Consumer<E> consumer);

  /**
   * Drains up to the specified number of elements from this queue, passing each to the given {@link
   * Consumer}.
   *
   * @param consumer the consumer that will process each element; must not be {@code null}
   * @param limit the maximum number of elements to drain
   * @return the actual number of elements drained (maybe less than {@code limit})
   */
  int drain(Consumer<E> consumer, int limit);

  /**
   * Fills the queue with elements supplied by the given {@link Supplier}, up to the specified limit
   * or until the queue becomes full.
   *
   * @param supplier the supplier that provides elements to insert; must not be {@code null}
   * @param limit the maximum number of elements to insert
   * @return the number of elements successfully added (maybe less than {@code limit})
   */
  int fill(@Nonnull Supplier<? extends E> supplier, int limit);

  /**
   * Returns the number of additional elements that can be inserted into this queue without
   * exceeding its capacity.
   *
   * @return the number of remaining slots available for insertion
   */
  int remainingCapacity();

  /**
   * Returns the total fixed capacity of this queue.
   *
   * @return the maximum number of elements this queue can hold
   */
  int capacity();
}

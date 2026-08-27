package datadog.common.queue;

import java.util.Collection;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * A bounded handoff point between producers and a consumer, with admission that never builds an
 * element it is going to reject.
 *
 * <p>Capacity is fixed by construction. A queue never grows in response to fullness: full means
 * drop and count. Admission reserves a slot before invoking any producer, so a rejected element is
 * never constructed at all — the guarantee that makes it safe to hand this a producer that
 * allocates heavily, since the allocation cannot happen on the path where it would be wasted.
 *
 * <p>Because the slot is claimed first, a producer runs while holding capacity a consumer may be
 * waiting on. Producers should build their element and nothing else: work that blocks, or that
 * takes appreciably longer than an allocation, stalls the consumer rather than merely the producer.
 *
 * <p>Consumption is synchronous and happens in the caller's frame; the boolean returned by the
 * {@code process} methods reports whether there was an item to work on, which is the signal a drain
 * loop needs, and says nothing about whether the consumer succeeded. A consumer that throws throws
 * out of {@code process} unless a {@link RetryStrategy} was supplied to handle it — the queue takes
 * no view on failure it was not given one for, and never logs.
 */
public interface WorkQueue<T> {

  /**
   * @return whether the element was admitted
   */
  boolean tryPut(T element);

  /**
   * Admits an element, constructing it only once a slot is reserved.
   *
   * @return whether the element was admitted
   */
  boolean tryPut(Producer<? extends T> producer);

  /**
   * Admits an element derived from {@code context}, constructing it only once a slot is reserved.
   *
   * @return whether the element was admitted
   */
  <C> boolean tryPut(C context, ContextualProducer<? super C, ? extends T> producer);

  /**
   * @return the elements that were not admitted, empty if all were
   */
  @SuppressWarnings("unchecked")
  Collection<T> tryPutBatch(T... elements);

  /**
   * @return the elements that were not admitted, empty if all were
   */
  Collection<T> tryPut(Collection<? extends T> elements);

  /**
   * Claims a place without supplying its element, for a caller whose work between claiming and
   * filling cannot be expressed as a {@link Producer}.
   *
   * <p>This is the escape hatch, and it is a sharper tool than the {@code tryPut} family: the
   * consumer cannot see past an open reservation, so one that is not promptly filled or closed
   * stalls it. Use try-with-resources.
   *
   * <p>Only the single-consumer backing offers it. Holding a place open depends on the consumer
   * being able to find the queue empty until the place is ready; where several consumers share a
   * queue one of them takes the unfilled place instead and can only spin on it, so those backings
   * refuse rather than deadlock.
   *
   * @return the claimed place, or {@code null} if there was no room
   * @throws UnsupportedOperationException if this queue has more than one consumer
   */
  Reservation<T> tryReserve();

  /**
   * Consumes one item, if there is one. A throwing consumer propagates.
   *
   * @return whether there was an item to consume
   */
  boolean process(Consumer<? super T> consumer);

  /**
   * Consumes one item, if there is one, handing a throwing consumer's failure to {@code
   * retryStrategy} rather than propagating it.
   *
   * @return whether there was an item to consume
   */
  boolean process(Consumer<? super T> consumer, RetryStrategy<T> retryStrategy);

  /**
   * Consumes one item, if there is one. A throwing consumer propagates.
   *
   * @return whether there was an item to consume
   */
  <C> boolean process(C context, BiConsumer<? super C, ? super T> consumer);

  /**
   * Consumes one item, if there is one, handing a throwing consumer's failure to {@code
   * retryStrategy} rather than propagating it.
   *
   * @return whether there was an item to consume
   */
  <C> boolean process(
      C context, BiConsumer<? super C, ? super T> consumer, RetryStrategy<T> retryStrategy);

  int size();

  /**
   * @return how many elements have been rejected on admission, or abandoned by a {@link
   *     RetryStrategy}, over this queue's lifetime
   */
  long dropped();

  /**
   * Stops future admission, leaving current contents alone so a consumer can finish its backlog.
   *
   * <p>A caller distinguishes "transiently full, worth retrying" from "permanently done" by asking
   * {@link #isClosed()}; the {@code boolean} returned by admission does not carry the difference.
   */
  void close();

  boolean isClosed();

  /** Discards current contents without affecting admission. */
  void clear();

  /**
   * Atomically {@link #close() closes} and {@link #clear() clears}.
   *
   * <p>Sequencing the two separately leaves a window — a producer already past the closed check, an
   * in-flight retry lease — through which work can land in a queue nothing will drain again.
   */
  void shutdown();
}

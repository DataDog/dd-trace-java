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
 * <p>Consumption is synchronous and happens in the caller's frame; the boolean returned by the
 * {@code process} methods reports whether there was an item to work on, which is the signal a drain
 * loop needs, and says nothing about whether the consumer succeeded.
 */
public interface Queue<T> {

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

  /** Admits every element the producer yields, pulling them as capacity allows. */
  void put(BatchProducer<? extends T> batchProducer);

  /**
   * @return whether there was an item to consume
   */
  boolean process(Consumer<? super T> consumer);

  /**
   * @return whether there was an item to consume
   */
  boolean process(Consumer<? super T> consumer, RetryStrategy<? super T> retryStrategy);

  /**
   * @return whether there was an item to consume
   */
  <C> boolean process(C context, BiConsumer<? super C, ? super T> consumer);

  /**
   * @return whether there was an item to consume
   */
  <C> boolean process(
      C context, BiConsumer<? super C, ? super T> consumer, RetryStrategy<? super T> retryStrategy);

  int size();

  /**
   * @return how many elements have been rejected over this queue's lifetime
   */
  long dropped();

  /**
   * Stops future admission, leaving current contents alone so a consumer can finish its backlog.
   *
   * <p>Rejection after closing is distinguishable from an ordinary full-capacity rejection, so a
   * caller can tell "transiently full, worth retrying" from "permanently done".
   */
  void close();

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

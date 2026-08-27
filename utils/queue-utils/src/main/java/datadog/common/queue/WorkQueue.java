package datadog.common.queue;

import java.util.Collection;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * A bounded handoff point between producers and a consumer, with admission that never builds an
 * element it is going to reject.
 *
 * <p>Capacity is fixed by construction. A queue never grows in response to fullness: full means
 * drop and count. Admission claims a place before invoking any producer, so a rejected element is
 * never constructed at all — the guarantee that makes it safe to hand this a producer that
 * allocates heavily, since the allocation cannot happen on the path where it would be wasted.
 *
 * <p>What is claimed is capacity, never a position, so a producer never holds up a consumer. It
 * does hold a place other producers could have used, though: work that blocks, or that takes
 * appreciably longer than an allocation, is paid for by everyone else admitting to this queue.
 * Producers should build their element and nothing else.
 *
 * <p>Prefer the producer forms, and treat {@link #tryReserve} as the fallback. The distinction is
 * {@code forEach} against {@code Iterator}: with a producer the queue owns the sequence, claiming
 * and building in the order it knows to be safe, and there is no protocol for a caller to get
 * wrong. A reservation hands that loop back — the caller must check {@link Reservation#granted},
 * must fill or close, and an abandoned one is capacity nobody can see or reclaim, the same way a
 * half-consumed iterator is state its collection cannot account for. Reach for it when the work
 * between claiming and filling genuinely will not fold into a callback, and use {@code tryPut}
 * everywhere else.
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
   * Admits an element derived from two contexts, constructing it only once a slot is reserved.
   *
   * @return whether the element was admitted
   * @see BiContextualProducer
   */
  <C1, C2> boolean tryPut(
      C1 first, C2 second, BiContextualProducer<? super C1, ? super C2, ? extends T> producer);

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
   * <p>What is reserved is capacity, not a position — {@link Reservation#fill} cannot be rejected,
   * and the element joins the queue where it is filled. Nothing is held open that a consumer could
   * be waiting on, so a thread may safely reserve and consume, but a reservation that is never
   * filled or closed leaks its capacity for good. Use try-with-resources.
   *
   * <p>Never {@code null}: a refusal comes back as a reservation that reports {@link
   * Reservation#granted} as {@code false} and discards anything filled into it. Nothing on the
   * refused path throws, so the try-with-resources is always safe; checking {@code granted} is what
   * lets the caller skip building an element the queue had no room for.
   *
   * @return the claimed capacity, or a refused reservation if there was none to claim
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

  /**
   * Consumes up to {@code limit} items, stopping early when the queue runs dry.
   *
   * <p>The limit is required, and there is no consume-until-empty form. Against live producers that
   * has no reason to ever return; on an unbounded backing there is not even a capacity to fall back
   * on as an implicit bound; and a {@link RetryStrategy} re-admits behind a consumer that is still
   * draining, so only a caller-named ceiling guarantees the batch ends. The limit is also the
   * caller's latency knob: a drain occupies its thread until it is done, which matters most where
   * that thread is shared with other subsystems.
   *
   * <p>A throwing consumer propagates, abandoning the rest of the batch. Items already consumed
   * stay consumed and the count is lost with the stack unwind, so a caller that needs it should
   * drain in smaller batches or handle failure per item with a {@link RetryStrategy}.
   *
   * @return how many items were consumed, which is {@code limit} when the batch filled and there
   *     may be more waiting
   */
  int process(int limit, Consumer<? super T> consumer);

  /**
   * Consumes up to {@code limit} items, stopping early when the queue runs dry.
   *
   * @return how many items were consumed
   * @see #process(int, Consumer)
   */
  <C> int process(int limit, C context, BiConsumer<? super C, ? super T> consumer);

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

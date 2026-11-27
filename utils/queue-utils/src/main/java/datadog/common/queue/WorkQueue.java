package datadog.common.queue;

import datadog.trace.api.function.Strategy;
import datadog.trace.api.function.StrategyConsumer;
import java.util.Collection;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * A handoff queue that claims capacity before invoking a producer.
 *
 * <p>A full or closed queue does not invoke the producer. This lets callers defer construction and
 * destructive reads until capacity is available. {@link #tryPut(Object)} accepts work already built
 * by the caller. Bounded factories fix capacity at construction; an unbounded queue requires {@link
 * WorkQueues#createUnboundedMpmcQueue()}.
 *
 * <p>A claim reserves capacity without holding a position in the backing while the element is
 * built. Keep producer callbacks short: their claims withhold capacity from other producers. Prefer
 * producer callbacks; use {@link #tryReserve()} when the work cannot fit one. Check {@link
 * Reservation#granted()} before building and close unfilled reservations to release capacity.
 *
 * <p>Admission reports refusals through return values or an optional {@link RejectHandler}. Batch
 * overloads return admitted counts or rejected elements. The queue keeps no drop count; callers
 * handle the outcomes they need to account for.
 *
 * <p>Processing runs on the calling thread. Boolean results report whether an item was found; they
 * say nothing about whether the consumer succeeded. Plain processing propagates consumer failures;
 * the retry and handler variants delegate them to a {@link RetryStrategy} or {@link
 * ExceptionHandler}.
 *
 * <p>Elements must be non-null and are checked before any capacity is claimed. Producers,
 * consumers, retry strategies and exception handlers must be non-null too, but are marked {@link
 * Nonnull} rather than checked; a null function object is a caller bug with unspecified effect.
 * Contexts and the optional reject handler may be null. A producer may return null to decline work
 * and release its claim. Single-element producer overloads return false for both declines and
 * refusals. A null element in a batch throws and abandons the remaining elements; earlier
 * admissions remain.
 *
 * @param <T> the work item type
 */
public interface WorkQueue<T> {

  /**
   * @return whether the element was admitted
   * @throws NullPointerException if the element is null, thrown before a place is claimed
   */
  boolean tryPut(T element);

  /**
   * Admits an element, constructing it only once a slot is reserved.
   *
   * <p>A producer returning {@code null} declines: its place goes back, nothing is admitted, and
   * nothing was lost. The {@code false} that comes back is the same {@code false} a full queue
   * gives, so a caller whose producer declines conditionally -- a drain that skips a counter
   * sitting at zero, say -- cannot tell "nothing to send" from "no room" from this return alone.
   * {@link #tryPutBatch(Collection, Object, BiContextualProducer)} separates the two, by reporting
   * how many of the source elements it admitted.
   *
   * @return whether the element was admitted, which is {@code false} both for a refusal and for a
   *     producer that declined
   */
  @StrategyConsumer
  boolean tryPut(@Nonnull @Strategy Producer<? extends T> producer);

  /**
   * Admits an element derived from {@code context}, constructing it only once a slot is reserved.
   *
   * @return whether the element was admitted; a producer returning {@code null} declines, and reads
   *     as a refusal here -- see {@link #tryPut(Producer)}
   */
  @StrategyConsumer
  <C> boolean tryPut(
      C context, @Nonnull @Strategy ContextualProducer<? super C, ? extends T> producer);

  /**
   * Admits an element derived from two contexts, constructing it only once a slot is reserved.
   *
   * @return whether the element was admitted; a producer returning {@code null} declines, and reads
   *     as a refusal here -- see {@link #tryPut(Producer)}
   * @see BiContextualProducer
   */
  @StrategyConsumer
  <C1, C2> boolean tryPut(
      C1 first,
      C2 second,
      @Nonnull @Strategy BiContextualProducer<? super C1, ? super C2, ? extends T> producer);

  /**
   * @return the elements that were not admitted, empty if all were
   */
  @SuppressWarnings("unchecked")
  Collection<T> tryPutBatch(T... elements);

  /**
   * @return the elements that were not admitted, empty if all were
   */
  Collection<T> tryPutBatch(Collection<? extends T> elements);

  /**
   * Transforms source elements after claiming capacity for each one.
   *
   * <p>A null result declines a source element and releases its claim. If no claim is granted, the
   * producer is not called for the remaining source elements. The difference between the source
   * size and the admitted count therefore includes both declines and refusals. Use a caller-owned
   * loop if every source element needs processing regardless of admission.
   *
   * <p>The source is a collection to require a finite batch: concurrent consumers could keep making
   * room indefinitely for an unbounded iterable.
   *
   * @param <E> the source element type
   * @param <C> the shared context type
   * @param source elements to transform
   * @param context value shared by producer calls; may be null
   * @param producer creates an element for each granted claim; may return null to decline
   * @return the number of elements admitted
   */
  @StrategyConsumer
  <E, C> int tryPutBatch(
      Collection<? extends E> source,
      C context,
      @Nonnull @Strategy BiContextualProducer<? super E, ? super C, ? extends T> producer);

  /**
   * As {@link #tryPutBatch(Collection, Object, BiContextualProducer)}, handing each source element
   * it could not admit to {@code onRejected} on the way past.
   *
   * <p>Elements the producer declined do not reach the handler; refusals do. See {@link
   * RejectHandler} for the one place that line blurs.
   *
   * @return how many elements were admitted
   * @see RejectHandler
   */
  @StrategyConsumer
  <E, C> int tryPutBatch(
      Collection<? extends E> source,
      C context,
      @Nonnull @Strategy BiContextualProducer<? super E, ? super C, ? extends T> producer,
      @Nullable @Strategy RejectHandler<? super E> onRejected);

  /**
   * Claims capacity for work that cannot be built in a producer callback.
   *
   * <p>The reservation holds no position in the backing. Its element joins the queue when filled,
   * so an open reservation does not block consumers. Use try-with-resources to release unfilled
   * reservations; abandoning one permanently consumes its capacity. Keep the reservation on the
   * reserving thread.
   *
   * <p>A refused claim returns a non-null reservation that ignores fill. Check {@link
   * Reservation#granted()} before building the element.
   *
   * @return a granted or refused reservation
   */
  Reservation<T> tryReserve();

  /**
   * Consumes one item, if there is one. A throwing consumer propagates.
   *
   * @return whether there was an item to consume
   */
  boolean process(@Nonnull Consumer<? super T> consumer);

  /**
   * Consumes one item, if there is one, handing a throwing consumer's failure to {@code
   * retryStrategy} rather than propagating it.
   *
   * @return whether there was an item to consume
   */
  boolean processOrRetry(
      @Nonnull Consumer<? super T> consumer, @Nonnull @Strategy RetryStrategy<T> retryStrategy);

  /**
   * Consumes one item, if there is one, handing a throwing consumer's failure to {@code
   * exceptionHandler} rather than propagating it. The item is dropped.
   *
   * @return whether there was an item to consume
   */
  boolean processOrHandle(
      @Nonnull Consumer<? super T> consumer,
      @Nonnull @Strategy ExceptionHandler<? super T> exceptionHandler);

  /**
   * Consumes one item, if there is one. A throwing consumer propagates.
   *
   * @return whether there was an item to consume
   */
  <C> boolean process(C context, @Nonnull BiConsumer<? super C, ? super T> consumer);

  /**
   * Consumes one item, if there is one, handing a throwing consumer's failure to {@code
   * retryStrategy} rather than propagating it.
   *
   * @return whether there was an item to consume
   */
  <C> boolean processOrRetry(
      C context,
      @Nonnull BiConsumer<? super C, ? super T> consumer,
      @Nonnull @Strategy RetryStrategy<T> retryStrategy);

  /**
   * Consumes one item, if there is one, handing a throwing consumer's failure to {@code
   * exceptionHandler} rather than propagating it. The item is dropped.
   *
   * @return whether there was an item to consume
   */
  <C> boolean processOrHandle(
      C context,
      @Nonnull BiConsumer<? super C, ? super T> consumer,
      @Nonnull @Strategy ExceptionHandler<? super T> exceptionHandler);

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
  int process(int limit, @Nonnull Consumer<? super T> consumer);

  /**
   * Consumes up to {@code limit} items, stopping early when the queue runs dry.
   *
   * @return how many items were consumed
   * @see #process(int, Consumer)
   */
  <C> int process(int limit, C context, @Nonnull BiConsumer<? super C, ? super T> consumer);

  /**
   * An O(1) count of the places currently spent: elements held, plus places claimed by producers
   * that have not stored into the queue yet.
   *
   * <p>A snapshot, and never outside {@code 0..capacity}: claimants racing at the boundary can
   * transiently spend past the bound before backing out, and the report is clamped rather than
   * allowed to show that. So a caller may see {@code capacity} on a queue that is about to have
   * room, but never a number it cannot act on.
   *
   * @return how much of the bound is in use, from zero to the capacity the queue was built with
   */
  int size();

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
   * Closes admission and discards queued elements.
   *
   * <p>This operation is not atomic. Producers, reservations, and retries that claimed capacity
   * before the close may still store after the discard. Quiesce them first if the queue must be
   * empty afterward.
   */
  void shutdown();
}

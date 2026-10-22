package datadog.common.queue;

import datadog.trace.api.function.Strategy;
import datadog.trace.api.function.StrategyConsumer;
import java.util.Collection;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * A bounded handoff point between producers and a consumer, with admission that never builds an
 * element it is going to reject.
 *
 * <h2>Why to use this instead of a queue and a call site</h2>
 *
 * Every failure below was found in this tree, in code that offers into a {@code
 * java.util.concurrent} queue by hand. They are not hypothetical, and none of them is anyone's
 * carelessness -- each is what the shape of {@code offer} invites. The last column is the honest
 * one: this API makes some of them impossible, some merely unlikely, one it only makes explicit,
 * and one it does not address at all.
 *
 * <table border="1">
 *   <caption>Hand-rolled admission failures, and what this API does about them</caption>
 *   <tr><th>Failure</th><th>Seen as</th><th>Here</th></tr>
 *   <tr>
 *     <td>The refusal is discarded, so a drop is invisible</td>
 *     <td>Seven call sites in {@code RumInjectorMetrics}; two in {@code WafMetricCollector}, which
 *         tests the return at a dozen sites and drops it at two; {@code ProductChangeCollector};
 *         {@code IntegrationsCollector}</td>
 *     <td><b>Not addressed.</b> Refusal is reported only through the return value, so a caller
 *         that ignores it loses the element as silently as before. A drop counter owned by the
 *         queue would cover this and is deliberately not in this version -- every other refusal
 *         here is already reported synchronously, so it would have been a safety net for a
 *         negligent caller rather than new information, and nothing yet surfaces it. What does
 *         help is that {@link #tryPutBatch} answers for a whole drain at once, leaving one return
 *         to ignore where a hand-rolled loop leaves one per element</td>
 *   </tr>
 *   <tr>
 *     <td>State is destroyed to build an element that is then refused, losing it permanently</td>
 *     <td>{@code WafMetricCollector} does {@code getAndSet(i, 0)} on a counter, then abandons the
 *         metric if the queue is full -- the delta is gone. {@code CoreMetricCollector} carries a
 *         comment about avoiding exactly this, so it is known</td>
 *     <td><b>Impossible</b> if admission is reached through a producer or a {@link Reservation}: the
 *         place is claimed before the source is consumed, so there is no window in which a caller
 *         has destroyed something it cannot hand off</td>
 *   </tr>
 *   <tr>
 *     <td>The first refusal abandons the rest of the batch, silently</td>
 *     <td>{@code WafMetricCollector} returns out of its publish loop on a failed offer</td>
 *     <td><b>Impossible.</b> {@link #tryPutBatch} reports how many it admitted, or hands back the
 *         elements it refused, so a partial admission is a number rather than a guess</td>
 *   </tr>
 *   <tr>
 *     <td>A capacity check that another thread invalidates before the offer</td>
 *     <td>{@code CoreMetricCollector}'s {@code remainingCapacity() == 0};
 *         {@code FlagEvaluationWriterImpl}'s {@code size() < capacity()}, whose javadoc correctly
 *         calls itself best-effort</td>
 *     <td><b>Impossible.</b> The check <i>is</i> the claim -- a granted place cannot be taken by a
 *         racing producer, so there is no check-then-act to lose</td>
 *   </tr>
 *   <tr>
 *     <td>The element is built before anyone asks whether there is room</td>
 *     <td>Most sites, since the element is the argument to {@code offer}</td>
 *     <td><b>Unlikely.</b> The producer forms make the cheap order the natural one, but
 *         {@link #tryPut(Object)} still lets a caller build first, on purpose, for elements that
 *         already exist</td>
 *   </tr>
 *   <tr>
 *     <td>An unbounded queue, so pressure becomes heap</td>
 *     <td>{@code ProductChangeCollector} and {@code IntegrationsCollector} are
 *         {@code new LinkedBlockingQueue<>()}</td>
 *     <td><b>Only made explicit.</b> A bound is required unless a caller names
 *         {@code WorkQueues.createUnboundedMpmcQueue}, which is a decision rather than a default</td>
 *   </tr>
 * </table>
 *
 * <p>Worth reading {@code FlagEvaluationWriterImpl} as the counter-example: it is bounded, it
 * counts its drops, it surfaces the count as a metric, and it documents its own race honestly. It
 * is proof the discipline is achievable by hand -- at the cost of that team working it out and
 * writing it down themselves, which is the cost this module exists to pay once. Note that its drop
 * counter is the one part of it this API does not replace: counting and publishing a refusal stays
 * the caller's, which is the same division {@code FlagEvaluationWriterImpl} already chose.
 *
 * <p>Capacity is fixed by construction. A queue never grows in response to fullness: full means
 * refuse. Admission claims a place before invoking any producer, so a rejected element is never
 * constructed at all — the guarantee that makes it safe to hand this a producer that allocates
 * heavily, since the allocation cannot happen on the path where it would be wasted.
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
 * out of {@code process} — the queue takes no view on failure it was not given one for, and never
 * logs. Say what should happen instead by calling {@link #processOrRetry} with a {@link
 * RetryStrategy}, or {@link #processOrHandle} with an {@link ExceptionHandler} when the answer is
 * only ever to record it and move on. Those are separate names rather than overloads because a
 * lambda or method reference cannot always tell two same-arity callbacks apart.
 *
 * <p>Nulls carry meaning in three places and are a bug everywhere else. A <b>context</b> may be
 * null: the queue carries it to a producer or consumer and never looks at it, so an absent one is
 * the caller's business. An optional {@link RejectHandler} may be null, which says exactly what the
 * overload without it says. And a <b>producer's return</b> may be null, which is that producer
 * declining the element it was asked to build — the place goes back and nothing is admitted, which
 * reads as a refusal to the caller even though nothing was lost.
 *
 * <p>Everything else is required. An <b>element</b> is never null, because neither backing can hold
 * one: there is no outcome to report, so {@code tryPut} and {@link Reservation#fill fill} throw
 * instead of returning, and they throw before claiming a place so that a call with a bug in it
 * costs the queue nothing. A null inside a batch throws partway through, abandoning the rest.
 * <b>Producers, consumers, retry strategies and exception handlers</b> are required too, but are
 * marked {@link Nonnull} rather than checked: a null function object is a caller bug, and checking
 * for one would cost every call inlining budget. Its effect is unspecified, and may cost the item a
 * processing call was meant to consume.
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

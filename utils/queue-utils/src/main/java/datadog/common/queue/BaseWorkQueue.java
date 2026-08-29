package datadog.common.queue;

import static java.util.Collections.emptyList;

import datadog.trace.api.function.Strategy;
import datadog.trace.api.function.StrategyConsumer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Everything a {@link WorkQueue} does that does not depend on how elements are stored: the bound,
 * admission, reservations, the closed state, drop counting, and the consume-and-maybe-retry cycle.
 *
 * <p>Subclasses supply two storage primitives, {@link #store} and {@link #retrieve}, and neither
 * needs to enforce anything. The bound lives here, as a count of places still available: admission
 * spends one before it builds or stores anything, consumption returns one, and a reservation is
 * simply a spent place with nothing in it yet. That is why the storage primitives can be as thin as
 * they are, and why both backings admit and reserve through exactly the same code.
 *
 * <p>The counter costs one atomic add per admission and one per consumption. On a backing that
 * could have leaned on its own bound that is a real tax, paid for a uniform contract: every backing
 * can reserve capacity, nothing has to hold a position open, so no consumer can be stalled by a
 * reservation and no reservation can deadlock a thread that also consumes.
 */
abstract class BaseWorkQueue<T> implements WorkQueue<T> {

  /**
   * Wraps an item that has already failed, carrying its attempt count back into the queue. Only
   * allocated on the failure path, so the common case stores the element itself.
   */
  private static final class Retry<T> {
    final T item;
    final int attempt;

    Retry(T item, int attempt) {
      this.item = item;
      this.attempt = attempt;
    }
  }

  /** Non-capturing adapters, so the producer forms share one admission path without allocating. */
  private static final ContextualProducer<Producer<Object>, Object> PRODUCE = Producer::produce;

  /**
   * The answer to every refused claim: a reservation that holds nothing, discards whatever is
   * filled into it, and has nothing to give back. It holds no state, so one instance serves every
   * queue and every element type.
   *
   * <p>Filling it is a no-op rather than a throw. The queue is full exactly when a caller can least
   * afford a surprise, and an exception raised only under backpressure is a bug that waits for
   * production to appear. The drop is already counted, by {@link #tryReserve} at the moment of
   * refusal.
   */
  private final LongAdder dropped = new LongAdder();

  /**
   * Subtracted from {@link #state} once, by {@link #close()}. Closing then costs no flag of its
   * own: it drives the permit count so far negative that no claim can ever succeed again, so a
   * closed queue refuses through the same comparison that a full one does and admission has one
   * word to read instead of two that have to agree.
   *
   * <p>Large enough to be unreachable from either side. Permits start at no more than {@link
   * Integer#MAX_VALUE} and only places actually claimed are ever given back, so releases after a
   * close cannot climb the offset; and the count is a {@code long} precisely because an unbounded
   * queue seeds it with {@code Integer.MAX_VALUE}, which leaves an {@code int} no room above the
   * bound to put this.
   */
  private static final long CLOSED_OFFSET = 1L << 40;

  /** Any state below this has {@link #CLOSED_OFFSET} applied to it, and nothing else can be. */
  private static final long CLOSED_MARK = -(1L << 39);

  /**
   * Places still available, not places used, biased by {@link #CLOSED_OFFSET} once closed. The
   * bound is then a comparison against zero rather than against a capacity that has to be loaded
   * and that an unbounded queue has to be branched around: seeded with {@link Integer#MAX_VALUE} it
   * is a queue no backlog can exhaust, on the same code path as any other.
   */
  private final AtomicLong state;

  private final int capacity;

  BaseWorkQueue(int capacity) {
    this.capacity = capacity;
    this.state = new AtomicLong(capacity);
  }

  /** The places left, with the closed bias taken back off. */
  private static long permits(long state) {
    return state < CLOSED_MARK ? state + CLOSED_OFFSET : state;
  }

  /**
   * Stores an element in a place already claimed for it, so this can only fail if the backing
   * refuses for a reason of its own.
   *
   * @return whether the element was stored
   */
  /**
   * The one call site every backing funnels through, which is why the count of backings loaded in a
   * process is an admission cost and not only a dispatch cost. At one or two implementations this
   * site is free; a third makes it megamorphic, measured at 24 bytes and roughly three times the
   * time per call — paid by callers that only ever touch one backing. A third backing is therefore
   * a decision about every existing caller, and the point at which to replace this template method
   * with a per-caller strategy so the sites stay separate.
   */
  abstract boolean store(Object element);

  /**
   * @return the next stored object, or {@code null} if there was none
   */
  abstract Object retrieve();

  /**
   * Spends a place, and gives it back if there was none to spend, rather than looping on a
   * compare-and-set. Admission costs one atomic add, with a second only on the path that was going
   * to be rejected anyway — and no retry under contention, which is where a CAS loop is at its
   * worst.
   *
   * <p>A plain read comes first, and it is what makes a refusal cheap. Without it a rejected
   * admission paid two read-modify-writes on the one line every producer is already fighting over,
   * at the capacity boundary, which is exactly where the most threads arrive at once -- {@code
   * ContendedAdmissionBenchmark} priced that at roughly 960ns against 3.4ns for the same rejection
   * taken on the backing's own producer index. A queue that is full, or closed, now turns a
   * claimant away with a load. The decrement stays authoritative, so the bound is unaffected: the
   * read can only cause a refusal, never an admission.
   *
   * <p>The bound itself is exact: the queue never holds more than {@code capacity} elements and
   * open reservations together. What is approximate is who gets turned away. Claimants racing at
   * the boundary can drive the count below zero between them and all give their places back, so an
   * admission can be rejected while the queue is a place or two short of full. That only happens
   * when it is already at the boundary, where the caller is dropping work regardless.
   */
  private boolean claimPlace() {
    if (state.get() < 1) {
      return false;
    }
    if (state.decrementAndGet() >= 0) {
      return true;
    }
    state.incrementAndGet();
    return false;
  }

  private void releasePlace() {
    state.incrementAndGet();
  }

  /**
   * Counts nothing, unlike the producer admissions below. This one is shared with the retry path,
   * where a refusal is a step rather than an outcome: a strategy handed a refused retry may still
   * place the item somewhere else, and only its {@link RetryStrategy#onFailure} return says whether
   * the item was finally lost. Each caller counts its own outcome, once.
   */
  private boolean admit(Object element) {
    if (!claimPlace()) {
      return false;
    }
    if (store(element)) {
      return true;
    }
    releasePlace();
    return false;
  }

  @StrategyConsumer
  private <C> boolean admit(
      C context, @Strategy ContextualProducer<? super C, ? extends T> producer) {
    if (!claimPlace()) {
      dropped.increment();
      return false;
    }
    T element;
    try {
      element = producer.produce(context);
    } catch (Throwable t) {
      releasePlace();
      throw t;
    }
    return storeOrRelease(element);
  }

  @StrategyConsumer
  private <C1, C2> boolean admit(
      C1 first,
      C2 second,
      @Strategy BiContextualProducer<? super C1, ? super C2, ? extends T> producer) {
    if (!claimPlace()) {
      dropped.increment();
      return false;
    }
    T element;
    try {
      element = producer.produce(first, second);
    } catch (Throwable t) {
      releasePlace();
      throw t;
    }
    return storeOrRelease(element);
  }

  /**
   * The per-source-element half of {@link #tryPutBatch(Collection, Object, BiContextualProducer)}.
   *
   * <p>Three outcomes, two of which report as not admitted for different reasons. A decline is the
   * caller's own decision, so it gives its place back and counts nothing. A refusal — no place to
   * claim, or a backing that would not take what was produced — gives the place back where there is
   * one and counts a drop.
   *
   * @return whether the source element was admitted
   */
  @StrategyConsumer
  private <E, C> boolean admitEach(
      E element,
      C context,
      @Strategy BiContextualProducer<? super E, ? super C, ? extends T> producer,
      @Strategy RejectHandler<? super E> onRejected) {
    if (!claimPlace()) {
      reject(element, onRejected);
      return false;
    }
    T produced;
    try {
      produced = producer.produce(element, context);
    } catch (Throwable t) {
      releasePlace();
      throw t;
    }
    if (produced == null) {
      // Declined. The place goes back and nothing is counted, because nothing was lost.
      releasePlace();
      return false;
    }
    if (store(produced)) {
      return true;
    }
    releasePlace();
    reject(element, onRejected);
    return false;
  }

  /** {@code null} rather than a no-op handler, so the count-only form adds a test and no call. */
  @StrategyConsumer
  private <E> void reject(E element, @Strategy RejectHandler<? super E> onRejected) {
    dropped.increment();
    if (onRejected != null) {
      onRejected.onRejected(element);
    }
  }

  /**
   * The tail of every producer admission. A {@code null} is the producer declining, which is the
   * caller's own decision: the place goes back and nothing is counted, because nothing was lost. A
   * backing that would not take what was produced is a refusal, and is counted. Same three outcomes
   * as {@link #admitEach}, which walks a source instead of taking one element.
   */
  private boolean storeOrRelease(T element) {
    if (element == null) {
      releasePlace();
      return false;
    }
    if (store(element)) {
      return true;
    }
    releasePlace();
    dropped.increment();
    return false;
  }

  /**
   * A place spent ahead of the element that will use it. Filling can only ever store, because the
   * room was already taken; abandoning gives the room back. Nothing is held open in the backing, so
   * a consumer never has to wait on one.
   *
   * <p>Both outcomes come from one allocation site. A refusal could be a shared singleton, and that
   * is the more obvious design: it saves the allocation on the path that already lost. What it
   * costs is paid by a caller that sees both outcomes at one site. Returning either a fresh
   * reservation or a static merges an allocation with a globally reachable reference at a phi, and
   * escape analysis gives up on the merge, so a reservation that would have been scalar-replaced
   * away is allocated for real — {@code AdmissionBenchmark.reserveMixed} measures 12 bytes per call
   * that way and zero this way, on JDK 17. JDK 21's allocation-merge support does not rescue it:
   * that covers merges of non-escaping allocations and null, never a static.
   *
   * <p>The condition matters, because it is not every caller. A site that only ever sees one
   * outcome — a queue that is effectively always accepting, or the drain loop's always-full
   * counterpart — has its other branch pruned, and there is no merge left to defeat anything; both
   * designs measure zero there. So this is insurance for the caller sitting at the capacity
   * boundary rather than a saving for everyone. It is free insurance, which is the reason to take
   * it: one allocation site is no worse anywhere, and it also keeps {@link #fill} and {@link
   * #close} monomorphic for callers that never see a refusal, and drops an unchecked cast.
   *
   * <p>A refused reservation starts out {@code done}, which is what makes it inert: there is no
   * place to give back and nothing to store, and both methods already short-circuit on that flag.
   *
   * <p>Static, with the queue handed in, rather than an inner class holding it implicitly. The
   * reference is a field of this object either way, so nothing changes at runtime; what changes is
   * that a reader can see it. That matters here more than it usually would, because the shape above
   * is asking escape analysis to delete this object and promote its fields to locals — so the field
   * count is the subject, and a hidden field is a hidden part of the subject.
   */
  private static final class PlaceReservation<T> implements Reservation<T> {
    private final BaseWorkQueue<T> queue;
    private final boolean granted;
    private boolean done;

    PlaceReservation(BaseWorkQueue<T> queue, boolean granted) {
      this.queue = queue;
      this.granted = granted;
      this.done = !granted;
    }

    @Override
    public boolean granted() {
      return granted;
    }

    @Override
    public void fill(T element) {
      // Before the null check, so that filling a refusal stays silent: a caller that skipped
      // building an element has nothing but null to offer, and the refused path never throws.
      if (done) {
        return;
      }
      requireElement(element);
      done = true;
      queue.store(element);
    }

    @Override
    public void close() {
      // Only the reserving thread fills or closes, so a plain flag orders the two correctly.
      if (!done) {
        done = true;
        queue.releasePlace();
      }
    }
  }

  private Object take() {
    Object element = retrieve();
    if (element != null) {
      releasePlace();
    }
    return element;
  }

  private void discardAll() {
    while (take() != null) {
      // give every place back as it goes
    }
  }

  @Override
  public final int size() {
    // Claimants at the boundary can transiently drive the count below zero before backing out.
    return (int) Math.max(0, capacity - permits(state.get()));
  }

  @Override
  public final boolean tryPut(T element) {
    requireElement(element);
    if (!admit(element)) {
      dropped.increment();
      return false;
    }
    return true;
  }

  @Override
  @SuppressWarnings({"unchecked", "rawtypes"})
  public final boolean tryPut(Producer<? extends T> producer) {
    return admit(producer, (ContextualProducer) PRODUCE);
  }

  @Override
  public final <C> boolean tryPut(C context, ContextualProducer<? super C, ? extends T> producer) {
    return admit(context, producer);
  }

  @Override
  public final <C1, C2> boolean tryPut(
      C1 first, C2 second, BiContextualProducer<? super C1, ? super C2, ? extends T> producer) {
    return admit(first, second, producer);
  }

  @Override
  @SafeVarargs
  public final Collection<T> tryPutBatch(T... elements) {
    List<T> rejected = null;
    for (int i = 0; i < elements.length; i++) {
      T element = elements[i];
      if (!tryPut(element)) {
        if (rejected == null) {
          // Refusals run to the end far more often than not: once the queue is full it stays full
          // for the rest of the pass unless a consumer intervenes. Sizing for the remainder is an
          // exact fit in that case and an over-fit in the other, and either beats regrowing.
          rejected = new ArrayList<>(elements.length - i);
        }
        rejected.add(element);
      }
    }
    return rejected == null ? emptyList() : rejected;
  }

  @Override
  public final Collection<T> tryPutBatch(Collection<? extends T> elements) {
    List<T> rejected = null;
    int remaining = elements.size();
    for (T element : elements) {
      if (!tryPut(element)) {
        if (rejected == null) {
          rejected = new ArrayList<>(remaining);
        }
        rejected.add(element);
      }
      remaining--;
    }
    return rejected == null ? emptyList() : rejected;
  }

  @Override
  public final <E, C> int tryPutBatch(
      Collection<? extends E> source,
      C context,
      BiContextualProducer<? super E, ? super C, ? extends T> producer) {
    return tryPutBatch(source, context, producer, null);
  }

  @Override
  public final <E, C> int tryPutBatch(
      Collection<? extends E> source,
      C context,
      BiContextualProducer<? super E, ? super C, ? extends T> producer,
      RejectHandler<? super E> onRejected) {
    int admitted = 0;
    for (E element : source) {
      if (admitEach(element, context, producer, onRejected)) {
        admitted++;
      }
    }
    return admitted;
  }

  @Override
  public final Reservation<T> tryReserve() {
    boolean granted = claimPlace();
    if (!granted) {
      dropped.increment();
    }
    return new PlaceReservation<>(this, granted);
  }

  @Override
  public final boolean process(Consumer<? super T> consumer) {
    return processOrRetry(consumer, null);
  }

  @Override
  public final boolean processOrHandle(
      Consumer<? super T> consumer, ExceptionHandler<? super T> exceptionHandler) {
    Object raw = take();
    if (raw == null) {
      return false;
    }
    consume(raw, consumer, null, null, null, exceptionHandler);
    return true;
  }

  @Override
  public final boolean processOrRetry(
      Consumer<? super T> consumer, RetryStrategy<T> retryStrategy) {
    Object raw = take();
    if (raw == null) {
      return false;
    }
    consume(raw, consumer, null, null, retryStrategy, null);
    return true;
  }

  @Override
  public final <C> boolean process(C context, BiConsumer<? super C, ? super T> consumer) {
    return processOrRetry(context, consumer, null);
  }

  @Override
  public final <C> boolean processOrRetry(
      C context, BiConsumer<? super C, ? super T> consumer, RetryStrategy<T> retryStrategy) {
    Object raw = take();
    if (raw == null) {
      return false;
    }
    consume(raw, null, context, consumer, retryStrategy, null);
    return true;
  }

  @Override
  public final <C> boolean processOrHandle(
      C context,
      BiConsumer<? super C, ? super T> consumer,
      ExceptionHandler<? super T> exceptionHandler) {
    Object raw = take();
    if (raw == null) {
      return false;
    }
    consume(raw, null, context, consumer, null, exceptionHandler);
    return true;
  }

  @Override
  public final int process(int limit, Consumer<? super T> consumer) {
    return process(limit, consumer, null, null);
  }

  @Override
  public final <C> int process(int limit, C context, BiConsumer<? super C, ? super T> consumer) {
    return process(limit, null, context, consumer);
  }

  private <C> int process(
      int limit,
      Consumer<? super T> consumer,
      C context,
      BiConsumer<? super C, ? super T> biConsumer) {
    int consumed = 0;
    while (consumed < limit) {
      Object raw = take();
      if (raw == null) {
        break;
      }
      // Counted before the consumer runs: a throw carries the count away with it either way, and
      // an item handed over is consumed whether or not the consumer made anything of it.
      consumed++;
      consume(raw, consumer, context, biConsumer, null, null);
    }
    return consumed;
  }

  @SuppressWarnings("unchecked")
  private <C> void consume(
      Object raw,
      Consumer<? super T> consumer,
      C context,
      BiConsumer<? super C, ? super T> biConsumer,
      RetryStrategy<T> retryStrategy,
      ExceptionHandler<? super T> exceptionHandler) {
    T item;
    int attempt;
    if (raw instanceof Retry) {
      Retry<T> retried = (Retry<T>) raw;
      item = retried.item;
      attempt = retried.attempt;
    } else {
      item = (T) raw;
      attempt = 0;
    }
    if (retryStrategy == null && exceptionHandler == null) {
      // No strategy means no opinion about failure: the throw travels out to the caller's own
      // frame, where its existing error handling already lives. Swallowing it here would make a
      // queue the arbiter of an error policy nobody handed it.
      if (consumer != null) {
        consumer.accept(item);
      } else {
        biConsumer.accept(context, item);
      }
      return;
    }
    try {
      if (consumer != null) {
        consumer.accept(item);
      } else {
        biConsumer.accept(context, item);
      }
    } catch (Throwable failure) {
      if (exceptionHandler != null) {
        dropped.increment();
        exceptionHandler.handle(item, failure);
      } else if (!retryStrategy.onFailure(item, attempt + 1, failure, lease(attempt + 1))) {
        dropped.increment();
      }
    }
  }

  /** Allocated only once a consumer has thrown, and never escapes {@link #onFailure}. */
  private RetryQueue<T> lease(int attempt) {
    return new RetryQueue<T>() {
      @Override
      public boolean retry(T item) {
        // No counting here. A refused retry is one step of a decision the strategy is still
        // making; the item is counted lost exactly once, when onFailure reports it gave up.
        return admit(new Retry<>(item, attempt));
      }

      @Override
      @SuppressWarnings("unchecked")
      public boolean retry(T... items) {
        boolean all = items.length > 0;
        for (T item : items) {
          all &= retry(item);
        }
        return all;
      }
    };
  }

  /**
   * The one place the module says what a {@code null} element is. Neither backing can hold one, so
   * there is no outcome to report and nothing to count -- only a caller with a bug. Thrown before a
   * place is claimed, so a rejected call costs the queue nothing.
   */
  private static void requireElement(Object element) {
    if (element == null) {
      throw new NullPointerException("a queue cannot hold null");
    }
  }

  @Override
  public final long dropped() {
    return dropped.sum();
  }

  @Override
  public final void close() {
    long current;
    do {
      current = state.get();
      if (current < CLOSED_MARK) {
        // Already closed. Applying the offset twice would walk the state toward a second
        // threshold nothing checks, and the second close has nothing left to say.
        return;
      }
    } while (!state.compareAndSet(current, current - CLOSED_OFFSET));
  }

  @Override
  public final boolean isClosed() {
    return state.get() < CLOSED_MARK;
  }

  @Override
  public final void clear() {
    discardAll();
  }

  @Override
  public final void shutdown() {
    close();
    discardAll();
  }
}

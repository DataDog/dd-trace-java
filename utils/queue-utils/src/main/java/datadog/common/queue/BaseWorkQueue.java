package datadog.common.queue;

import static java.util.Collections.emptyList;

import datadog.trace.api.function.Strategy;
import datadog.trace.api.function.StrategyConsumer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
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
   * Everything the queue lost, counted once each: refused admissions, elements a backing would not
   * take, and items a retry strategy finally gave up on. Not a bound and not read on the admission
   * path, so a {@link LongAdder}'s striping is free here and its contended write is what matters.
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
   * The most places one call will claim at a time, however long the batch.
   *
   * <p>This is not a fairness knob. A claim drives the shared count down by its own size until the
   * unused part comes back, and every other producer's admission begins with a comparison of that
   * count against zero -- so an oversized claim turns concurrent single admissions away for the
   * width of the claim, at the boundary, where the most threads are arriving. The cap is a bound on
   * how many neighbours one batching producer can make refuse, which is why it is modest rather
   * than as large as batches get. Claims are also clamped to what the count says is available, so
   * this bounds the dip a claim takes deliberately; the rest is the race the single claim already
   * runs. {@code ContendedAdmissionBenchmark} prices both halves: what batching saves the batcher,
   * and what it costs the producers next to it.
   */
  private static final int MAX_BATCH_CLAIM = 32;

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
   * <p>A place has already been claimed, so a backing that can refuse transiently is expected to
   * retry rather than report the refusal — a {@code false} from here is taken as a drop and
   * counted, and there is no way for the queue to tell a structure saying "full" from one saying
   * "not yet". {@link MpmcWorkQueue} is the case that matters.
   *
   * <h2>What a third backing would cost here</h2>
   *
   * <p>This is one call site shared by every backing in the process, so its receiver profile is
   * global rather than per-caller: a queue used nowhere near yours still writes into it. At one or
   * two implementations C2 inlines through it; at three it stops, and {@code -XX:+PrintInlining}
   * reports {@code store} and {@code retrieve} as {@code failed to inline: virtual call} on both
   * JDK 17 and JDK 25. So the cliff is real and it is easy to fall off — a third backing is a
   * decision about every existing caller, not only about its own.
   *
   * <p>It is also, measured, worth one or two nanoseconds. {@code AdmissionBenchmark}'s {@code
   * THREE} arm is the standing version of that experiment: 20.8ns against 21.8ns per
   * admit-and-drain for {@code tryPut(Producer)}, the same shape across the other admission forms,
   * and no allocation difference at all. The reason is that an admit-and-drain pays two uncontended
   * atomics on the permit count and a compare-and-set inside the ring, and an out-of-line call is
   * little against memory ordering. Contention widens the atomics and narrows this further, and
   * batching does not change it either, because the drain still returns a place per element.
   *
   * <p>One path is worse: admitting through a {@link Reservation} costs about 30% more, because
   * {@link Reservation#fill}'s {@code store} sits at the end of a chain of optimizations that has
   * to survive a call that no longer folds away. The reservation is still scalar-replaced, so it is
   * time and not garbage — but a caller admitting that way is the one with something to lose.
   *
   * <p>Which is the useful form of the warning. Do not add a third backing to buy throughput,
   * because there is none here to buy; weigh it against a few percent and against the reader. And
   * if a path ever admits without touching an atomic, measure again there, because that is where
   * this would start to matter. The repair, if it comes to it, is to stop sharing the site: let
   * each backing implement the public interface and forward into this class, so the receiver is an
   * exact final type at the top of the inlining tree and the call devirtualizes by static
   * resolution rather than by profile.
   *
   * <p>None of that applies to a backing that holds two <i>structures</i> behind one type, which is
   * why {@link MpmcWorkQueue} branches on a field of its own rather than arriving here as two
   * classes. A predictable branch costs nothing and adds no receiver.
   *
   * @return whether the element was stored
   */
  abstract boolean store(Object element);

  /**
   * @return the next stored object, or {@code null} if there was none
   */
  abstract Object retrieve();

  /**
   * How many times {@link #discardAll} will re-read a backing that says empty while the count says
   * otherwise, before it believes the backing.
   */
  private static final int DRAIN_ATTEMPTS = 64;

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
   * Spends up to {@code wanted} places at once, and grants what was there rather than refusing
   * because all of it was not. {@link #claimPlace} is this sequence with {@code wanted} of one.
   *
   * <p>A batch that claimed all-or-nothing would refuse the whole call when one place was short,
   * which is a refusal the caller cannot distinguish from a full queue and cannot act on -- it had
   * room for all but one. Granting the shortfall exactly removes that outcome by construction,
   * without a retry at a smaller size: the initial read already says how large the claim can
   * usefully be, so the scaling down is a clamp against a load this path was paying anyway, not a
   * second trip.
   *
   * <p>The shape is {@link #claimPlace}'s, and deliberately so: one atomic add on the common path,
   * a second only where the count went negative, and no compare-and-set loop. It reads {@link
   * #state} raw rather than through {@link #permits}, for the same reason that one does -- a closed
   * queue is biased far below zero, so it fails the first comparison and is turned away by a load,
   * and a close landing mid-claim drives the count so negative that the whole claim is given back.
   *
   * <p>What a short grant means is therefore narrower than it looks. It is not proof the queue is
   * full: concurrent claimants can each back out and leave places neither of them took. Callers
   * loop until a claim comes back empty rather than treating the first short grant as the end.
   *
   * @return the places granted, between zero and {@code wanted}
   */
  private int claimPlaces(int wanted) {
    long available = state.get();
    if (available < 1) {
      return 0;
    }
    int ask = (int) Math.min(wanted, available);
    long after = state.addAndGet(-ask);
    if (after >= 0) {
      return ask;
    }
    // Short by exactly the deficit -- and if a close landed in between, the deficit is the whole
    // claim, so the offset is restored untouched.
    int give = (int) Math.min(-after, ask);
    state.addAndGet(give);
    return ask - give;
  }

  private void releasePlaces(int places) {
    if (places != 0) {
      state.addAndGet(places);
    }
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
   * The per-source-element half of {@link #tryPutBatch(Collection, Object, BiContextualProducer)},
   * entered with a place already claimed for this element by the batch's own claim.
   *
   * <p>Three outcomes, two of which report as not admitted for different reasons. A decline is the
   * caller's own decision, so it gives its place back and counts nothing. A backing that would not
   * take what was produced is a refusal: the place goes back and a drop is counted. The third way
   * to fail -- no place at all -- cannot arise here, because the caller does not enter without one.
   *
   * @return whether the source element was admitted
   */
  @StrategyConsumer
  private <E, C> boolean admitEachClaimed(
      E element,
      C context,
      @Strategy BiContextualProducer<? super E, ? super C, ? extends T> producer,
      @Strategy RejectHandler<? super E> onRejected) {
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

  /**
   * Empties the queue, giving every place back as it goes.
   *
   * <p>An empty read is not taken at face value while {@link #size} still reports work. A backing
   * may report empty with an element in it — the MPMC ring does, for the width of another thread's
   * publish — and stopping there would leave elements behind holding their places, which for {@link
   * #clear} and {@link #shutdown} is the difference between emptying the queue and appearing to.
   * The re-read is bounded, because {@code size} also counts places claimed by producers that have
   * not stored yet, and a producer still running would otherwise keep this loop here forever.
   */
  private void discardAll() {
    int emptyReads = 0;
    while (true) {
      if (take() != null) {
        emptyReads = 0;
      } else if (size() <= 0 || ++emptyReads >= DRAIN_ATTEMPTS) {
        return;
      }
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

  /**
   * One claim for a run of elements rather than one per element, which is what makes a batch worth
   * calling rather than a loop the caller could have written.
   *
   * <p>The loop stops on an empty claim, not on a short one. A short grant is not evidence the
   * queue is full -- see {@link #claimPlaces} -- so treating it as the end would drop elements
   * there was room for, which is the failure this shape exists to avoid.
   *
   * <p>What it does not remove is the backing's own per-element cost: a claimed place still has to
   * be stored, and the MPSC ring charges a producer-index compare-and-set for each one. This halves
   * the atomics per element on that backing, near enough; it does not get to one.
   */
  @Override
  @SafeVarargs
  public final Collection<T> tryPutBatch(T... elements) {
    List<T> rejected = null;
    int index = 0;
    int length = elements.length;
    while (index < length) {
      int granted = claimPlaces(Math.min(length - index, MAX_BATCH_CLAIM));
      if (granted == 0) {
        // Refusals run to the end far more often than not: once the queue is full it stays full
        // for the rest of the pass unless a consumer intervenes. Sizing for the remainder is an
        // exact fit in that case and an over-fit in the other, and either beats regrowing.
        if (rejected == null) {
          rejected = new ArrayList<>(length - index);
        }
        for (; index < length; index++) {
          T element = elements[index];
          requireElement(element);
          dropped.increment();
          rejected.add(element);
        }
        break;
      }
      int end = index + granted;
      try {
        for (; index < end; index++) {
          T element = elements[index];
          requireElement(element);
          if (!store(element)) {
            releasePlace();
            dropped.increment();
            if (rejected == null) {
              rejected = new ArrayList<>(length - index);
            }
            rejected.add(element);
          }
        }
      } catch (Throwable t) {
        // A null element throws out of the batch. The places claimed for it and for everything
        // after it in this run were never spent, and have to go back before the throw leaves.
        releasePlaces(end - index);
        throw t;
      }
    }
    return rejected == null ? emptyList() : rejected;
  }

  /** As {@link #tryPutBatch(Object[])}, over a collection. */
  @Override
  public final Collection<T> tryPutBatch(Collection<? extends T> elements) {
    List<T> rejected = null;
    int remaining = elements.size();
    Iterator<? extends T> source = elements.iterator();
    while (remaining > 0) {
      int granted = claimPlaces(Math.min(remaining, MAX_BATCH_CLAIM));
      if (granted == 0) {
        if (rejected == null) {
          rejected = new ArrayList<>(remaining);
        }
        while (source.hasNext()) {
          T element = source.next();
          requireElement(element);
          dropped.increment();
          rejected.add(element);
        }
        break;
      }
      int unspent = granted;
      try {
        while (unspent > 0 && source.hasNext()) {
          T element = source.next();
          requireElement(element);
          unspent--;
          remaining--;
          if (!store(element)) {
            releasePlace();
            dropped.increment();
            if (rejected == null) {
              rejected = new ArrayList<>(remaining + 1);
            }
            rejected.add(element);
          }
        }
      } catch (Throwable t) {
        releasePlaces(unspent);
        throw t;
      }
      if (unspent > 0) {
        // The collection ran out before the claim did -- size() lied, or lies under concurrent
        // modification. Give back what was never spent rather than losing it to the count.
        releasePlaces(unspent);
        break;
      }
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

  /**
   * As {@link #tryPutBatch(Object[])}, asking a producer for each element that already has a place.
   *
   * <p>A declined element gives its place straight back to the count, so it does not consume the
   * batch's room -- but it does consume this run's claim, which is why the loop keeps claiming
   * until one comes back empty rather than stopping at the first run that did not fill.
   */
  @Override
  public final <E, C> int tryPutBatch(
      Collection<? extends E> source,
      C context,
      BiContextualProducer<? super E, ? super C, ? extends T> producer,
      RejectHandler<? super E> onRejected) {
    int admitted = 0;
    int remaining = source.size();
    Iterator<? extends E> elements = source.iterator();
    while (remaining > 0) {
      int granted = claimPlaces(Math.min(remaining, MAX_BATCH_CLAIM));
      if (granted == 0) {
        // No place, so the producer is never asked -- the whole point of the API, kept at the end
        // of a batch as well as at the start of one.
        while (elements.hasNext()) {
          reject(elements.next(), onRejected);
        }
        break;
      }
      int unspent = granted;
      try {
        while (unspent > 0 && elements.hasNext()) {
          E element = elements.next();
          unspent--;
          remaining--;
          if (admitEachClaimed(element, context, producer, onRejected)) {
            admitted++;
          }
        }
      } catch (Throwable t) {
        releasePlaces(unspent);
        throw t;
      }
      if (unspent > 0) {
        releasePlaces(unspent);
        break;
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

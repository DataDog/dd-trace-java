package datadog.common.queue;

import static java.util.Collections.emptyList;

import datadog.trace.api.function.Strategy;
import datadog.trace.api.function.StrategyConsumer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Everything a {@link WorkQueue} does that does not depend on how elements are stored: the bound,
 * admission, reservations, the closed flag, drop counting, and the consume-and-maybe-retry cycle.
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
  private static final class Retried<T> {
    final T item;
    final int attempt;

    Retried(T item, int attempt) {
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
  private static final Reservation<Object> REFUSED =
      new Reservation<Object>() {
        @Override
        public boolean granted() {
          return false;
        }

        @Override
        public void fill(Object element) {}

        @Override
        public void close() {}
      };

  private final LongAdder dropped = new LongAdder();
  private volatile boolean closed;

  /**
   * Places still available, not places used. The bound is then a comparison against zero rather
   * than against a capacity that has to be loaded and that an unbounded queue has to be branched
   * around: seeded with {@link Integer#MAX_VALUE} it is a queue no backlog can exhaust, on the same
   * code path as any other.
   */
  private final AtomicInteger available;

  private final int capacity;

  BaseWorkQueue(int capacity) {
    this.capacity = capacity;
    this.available = new AtomicInteger(capacity);
  }

  /**
   * Stores an element in a place already claimed for it, so this can only fail if the backing
   * refuses for a reason of its own.
   *
   * @return whether the element was stored
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
   * <p>The bound itself is exact: the queue never holds more than {@code capacity} elements and
   * open reservations together. What is approximate is who gets turned away. Claimants racing at
   * the boundary can drive the count below zero between them and all give their places back, so an
   * admission can be rejected while the queue is a place or two short of full. That only happens
   * when it is already at the boundary, where the caller is dropping work regardless.
   */
  private boolean claimPlace() {
    if (available.decrementAndGet() >= 0) {
      return true;
    }
    available.incrementAndGet();
    return false;
  }

  private void releasePlace() {
    available.incrementAndGet();
  }

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

  private boolean storeOrRelease(T element) {
    if (element != null && store(element)) {
      return true;
    }
    releasePlace();
    return false;
  }

  /**
   * A place spent ahead of the element that will use it. Filling can only ever store, because the
   * room was already taken; abandoning gives the room back. Nothing is held open in the backing, so
   * a consumer never has to wait on one.
   */
  private final class PlaceReservation implements Reservation<T> {
    private boolean done;

    @Override
    public boolean granted() {
      return true;
    }

    @Override
    public void fill(T element) {
      if (element == null) {
        throw new NullPointerException("a queue cannot hold null");
      }
      if (!done) {
        done = true;
        store(element);
      }
    }

    @Override
    public void close() {
      // Only the reserving thread fills or closes, so a plain flag orders the two correctly.
      if (!done) {
        done = true;
        releasePlace();
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
  public int size() {
    // Claimants at the boundary can transiently drive the count below zero before backing out.
    return Math.max(0, capacity - available.get());
  }

  @Override
  public boolean tryPut(T element) {
    return record(!closed && admit(element));
  }

  @Override
  @SuppressWarnings({"unchecked", "rawtypes"})
  public boolean tryPut(Producer<? extends T> producer) {
    return record(!closed && admit(producer, (ContextualProducer) PRODUCE));
  }

  @Override
  public <C> boolean tryPut(C context, ContextualProducer<? super C, ? extends T> producer) {
    return record(!closed && admit(context, producer));
  }

  @Override
  public <C1, C2> boolean tryPut(
      C1 first, C2 second, BiContextualProducer<? super C1, ? super C2, ? extends T> producer) {
    return record(!closed && admit(first, second, producer));
  }

  @Override
  @SafeVarargs
  public final Collection<T> tryPutBatch(T... elements) {
    List<T> rejected = null;
    for (T element : elements) {
      if (!tryPut(element)) {
        if (rejected == null) {
          rejected = new ArrayList<>();
        }
        rejected.add(element);
      }
    }
    return rejected == null ? emptyList() : rejected;
  }

  @Override
  public Collection<T> tryPut(Collection<? extends T> elements) {
    List<T> rejected = null;
    for (T element : elements) {
      if (!tryPut(element)) {
        if (rejected == null) {
          rejected = new ArrayList<>();
        }
        rejected.add(element);
      }
    }
    return rejected == null ? emptyList() : rejected;
  }

  @Override
  @SuppressWarnings("unchecked")
  public Reservation<T> tryReserve() {
    if (closed || !claimPlace()) {
      dropped.increment();
      return (Reservation<T>) REFUSED;
    }
    return new PlaceReservation();
  }

  @Override
  public boolean process(Consumer<? super T> consumer) {
    return process(consumer, (RetryStrategy<T>) null);
  }

  @Override
  public boolean process(Consumer<? super T> consumer, RetryStrategy<T> retryStrategy) {
    Object raw = take();
    if (raw == null) {
      return false;
    }
    consume(raw, consumer, null, null, retryStrategy);
    return true;
  }

  @Override
  public <C> boolean process(C context, BiConsumer<? super C, ? super T> consumer) {
    return process(context, consumer, (RetryStrategy<T>) null);
  }

  @Override
  public <C> boolean process(
      C context, BiConsumer<? super C, ? super T> consumer, RetryStrategy<T> retryStrategy) {
    Object raw = take();
    if (raw == null) {
      return false;
    }
    consume(raw, null, context, consumer, retryStrategy);
    return true;
  }

  @Override
  public int process(int limit, Consumer<? super T> consumer) {
    return process(limit, consumer, null, null);
  }

  @Override
  public <C> int process(int limit, C context, BiConsumer<? super C, ? super T> consumer) {
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
      consume(raw, consumer, context, biConsumer, null);
    }
    return consumed;
  }

  @SuppressWarnings("unchecked")
  private <C> void consume(
      Object raw,
      Consumer<? super T> consumer,
      C context,
      BiConsumer<? super C, ? super T> biConsumer,
      RetryStrategy<T> retryStrategy) {
    T item;
    int attempt;
    if (raw instanceof Retried) {
      Retried<T> retried = (Retried<T>) raw;
      item = retried.item;
      attempt = retried.attempt;
    } else {
      item = (T) raw;
      attempt = 0;
    }
    if (retryStrategy == null) {
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
      if (!retryStrategy.onFailure(item, attempt + 1, failure, lease(attempt + 1))) {
        dropped.increment();
      }
    }
  }

  /** Allocated only once a consumer has thrown, and never escapes {@link #onFailure}. */
  private RetryQueue<T> lease(int attempt) {
    return new RetryQueue<T>() {
      @Override
      public boolean retry(T item) {
        if (closed || !admit(new Retried<>(item, attempt))) {
          dropped.increment();
          return false;
        }
        return true;
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

  private boolean record(boolean admitted) {
    if (!admitted) {
      dropped.increment();
    }
    return admitted;
  }

  @Override
  public long dropped() {
    return dropped.sum();
  }

  @Override
  public void close() {
    closed = true;
  }

  @Override
  public boolean isClosed() {
    return closed;
  }

  @Override
  public void clear() {
    discardAll();
  }

  @Override
  public void shutdown() {
    closed = true;
    discardAll();
  }
}

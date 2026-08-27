package datadog.common.queue;

import static java.util.Collections.emptyList;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Everything a {@link Queue} does that does not depend on how elements are stored: admission
 * bookkeeping, the closed flag, drop counting, and the consume-and-maybe-retry cycle.
 *
 * <p>Subclasses supply four storage primitives. {@link #admit(Object)} and {@link #admit(Object,
 * ContextualProducer)} must both claim a slot before storing anything, and the producing form must
 * not invoke the producer unless the claim succeeded — that is the contract this whole API exists
 * to provide.
 */
abstract class BaseQueue<T> implements Queue<T> {

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

  private static final ContextualProducer<BatchProducer<Object>, Object> NEXT = BatchProducer::next;

  private final LongAdder dropped = new LongAdder();
  private volatile boolean closed;

  /**
   * Stores an already-built element, claiming a slot first.
   *
   * @return whether a slot was claimed and the element stored
   */
  abstract boolean admit(Object element);

  /**
   * Claims a slot and only then invokes the producer to build the element.
   *
   * @return whether a slot was claimed and the element stored
   */
  abstract <C> boolean admit(C context, ContextualProducer<? super C, ? extends T> producer);

  /**
   * @return the next stored object, or {@code null} if there was none
   */
  abstract Object take();

  abstract void discardAll();

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
  @SuppressWarnings({"unchecked", "rawtypes"})
  public void put(BatchProducer<? extends T> batchProducer) {
    // Nothing is lost by stopping early: an element is pulled only once a slot is claimed, so
    // whatever we did not take is still held by the producer.
    while (!closed && batchProducer.hasNext() && admit(batchProducer, (ContextualProducer) NEXT)) {
      // keep pulling
    }
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
    try {
      if (consumer != null) {
        consumer.accept(item);
      } else {
        biConsumer.accept(context, item);
      }
    } catch (Throwable failure) {
      onFailure(item, attempt + 1, failure, retryStrategy);
    }
  }

  private void onFailure(T item, int attempt, Throwable failure, RetryStrategy<T> retryStrategy) {
    if (retryStrategy == null || !retryStrategy.onFailure(item, attempt, failure, lease(attempt))) {
      dropped.increment();
    }
  }

  /** Allocated only once a consumer has thrown, and never escapes {@link #onFailure}. */
  private RetryQueue<T> lease(int attempt) {
    return new RetryQueue<T>() {
      @Override
      @SuppressWarnings("unchecked")
      public boolean retry(T... items) {
        boolean all = items.length > 0;
        for (T item : items) {
          if (closed || !admit(new Retried<>(item, attempt))) {
            dropped.increment();
            all = false;
          }
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

package datadog.common.queue;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A {@link Queue} over a {@link ConcurrentLinkedQueue}: multi-producer, multi-consumer, optionally
 * bounded.
 *
 * <p>This backing exists to give call sites that cannot yet take an MPSC ring — because they have
 * several consumers, or no defensible capacity — the admission and lifecycle contract anyway, so
 * they can be migrated behind {@link Queue} first and re-backed later. It keeps the linked queue's
 * per-element node, so it does not deliver the allocation win; prefer {@link MpscBoundedQueue}.
 *
 * <p>The size counter is not merely bookkeeping. It is what makes the bound enforceable and {@link
 * #size()} constant-time, replacing the hand-rolled cap plus O(n) {@code ConcurrentLinkedQueue
 * .size()} walk that call sites otherwise pay on every admission.
 */
final class LinkedQueue<T> extends BaseQueue<T> {

  private final ConcurrentLinkedQueue<Object> queue = new ConcurrentLinkedQueue<>();
  private final AtomicInteger size = new AtomicInteger();
  private final int capacity;

  /**
   * @param capacity the bound, or {@link Integer#MAX_VALUE} to leave the queue unbounded
   */
  LinkedQueue(int capacity) {
    this.capacity = capacity;
  }

  @Override
  boolean admit(Object element) {
    if (!reserve()) {
      return false;
    }
    queue.offer(element);
    return true;
  }

  @Override
  <C> boolean admit(C context, ContextualProducer<? super C, ? extends T> producer) {
    if (!reserve()) {
      return false;
    }
    T element;
    try {
      element = producer.produce(context);
    } catch (Throwable t) {
      size.decrementAndGet();
      throw t;
    }
    queue.offer(element);
    return true;
  }

  private boolean reserve() {
    if (capacity == Integer.MAX_VALUE) {
      size.incrementAndGet();
      return true;
    }
    int current;
    do {
      current = size.get();
      if (current >= capacity) {
        return false;
      }
    } while (!size.compareAndSet(current, current + 1));
    return true;
  }

  @Override
  Object take() {
    Object element = queue.poll();
    if (element != null) {
      size.decrementAndGet();
    }
    return element;
  }

  @Override
  void discardAll() {
    while (take() != null) {
      // drain
    }
  }

  @Override
  public int size() {
    return size.get();
  }
}

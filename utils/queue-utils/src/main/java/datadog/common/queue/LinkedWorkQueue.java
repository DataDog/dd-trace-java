package datadog.common.queue;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A {@link WorkQueue} over a {@link ConcurrentLinkedQueue}: multi-producer, multi-consumer,
 * optionally bounded.
 *
 * <p>This backing exists to give call sites that cannot yet take an MPSC ring — because they have
 * several consumers, or no defensible capacity — the admission and lifecycle contract anyway, so
 * they can be migrated behind {@link WorkQueue} first and re-backed later. It keeps the linked
 * queue's per-element node, so it does not deliver the allocation win; prefer {@link
 * MpscWorkQueue}.
 *
 * <p>Reservations are not available here. Holding a place open needs the consumer to be able to see
 * that the place is not ready yet and simply find the queue empty; with several consumers, one of
 * them takes the place instead and has nothing to do but spin until it is filled — a single thread
 * that reserves and then drains would wait on itself forever. {@link MpscWorkQueue} has one
 * consumer and can offer the hatch safely.
 *
 * <p>The size counter is not merely bookkeeping. It is what makes the bound enforceable and {@link
 * #size()} constant-time, replacing the hand-rolled cap plus O(n) {@code ConcurrentLinkedQueue
 * .size()} walk that call sites otherwise pay on every admission.
 */
final class LinkedWorkQueue<T> extends BaseWorkQueue<T> {

  private final ConcurrentLinkedQueue<Object> queue = new ConcurrentLinkedQueue<>();
  private final AtomicInteger size = new AtomicInteger();
  private final int capacity;

  /**
   * @param capacity the bound, or {@link Integer#MAX_VALUE} to leave the queue unbounded
   */
  LinkedWorkQueue(int capacity) {
    this.capacity = capacity;
  }

  @Override
  boolean admit(Object element) {
    if (!claimPlace()) {
      return false;
    }
    queue.offer(element);
    return true;
  }

  @Override
  <C> boolean admit(C context, ContextualProducer<? super C, ? extends T> producer) {
    if (!claimPlace()) {
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

  private boolean claimPlace() {
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
    return poll();
  }

  private Object poll() {
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

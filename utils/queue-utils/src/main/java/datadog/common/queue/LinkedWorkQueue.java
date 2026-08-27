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
 * <p>A reservation here claims capacity and nothing else. There is no slot to hold, so the element
 * joins at the tail when it is filled and the queue keeps fill order rather than claim order — and,
 * because no place is ever open in the queue itself, no consumer can find one it has to wait on.
 * {@link MpscWorkQueue} pays for claim order with a consumer that cannot see past an open
 * reservation; this backing does not have that hazard because it does not offer that guarantee.
 *
 * <p>The permit counter is not merely bookkeeping. It is what makes the bound enforceable and
 * {@link #size()} constant-time, replacing the hand-rolled cap plus O(n) {@code
 * ConcurrentLinkedQueue.size()} walk that call sites otherwise pay on every admission. It costs one
 * atomic add per admission and one per consumption; a call site migrating off an uncapped {@code
 * ConcurrentLinkedQueue} gets a bound for less than its old size check cost.
 */
final class LinkedWorkQueue<T> extends BaseWorkQueue<T> {

  private final ConcurrentLinkedQueue<Object> queue = new ConcurrentLinkedQueue<>();

  /**
   * Places still available, not places used. Admission spends one and consumption returns it, so
   * the bound is a comparison against zero rather than against a capacity that has to be loaded and
   * that an unbounded queue has to be branched around: seeded with {@link Integer#MAX_VALUE} it is
   * a queue no backlog can exhaust, on the same code path as any other.
   */
  private final AtomicInteger available;

  private final int capacity;

  /**
   * @param capacity the bound, or {@link Integer#MAX_VALUE} to leave the queue unbounded
   */
  LinkedWorkQueue(int capacity) {
    this.capacity = capacity;
    this.available = new AtomicInteger(capacity);
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
      available.incrementAndGet();
      throw t;
    }
    queue.offer(element);
    return true;
  }

  @Override
  <C1, C2> boolean admit(
      C1 first, C2 second, BiContextualProducer<? super C1, ? super C2, ? extends T> producer) {
    if (!claimPlace()) {
      return false;
    }
    T element;
    try {
      element = producer.produce(first, second);
    } catch (Throwable t) {
      available.incrementAndGet();
      throw t;
    }
    queue.offer(element);
    return true;
  }

  /**
   * Spends a place, and gives it back if there was none to spend, rather than looping on a
   * compare-and-set. Admission costs one atomic add, with a second only on the path that was going
   * to be rejected anyway — and no retry under contention, which is where a CAS loop is at its
   * worst.
   *
   * <p>The cap itself is exact: the queue never holds more than {@code capacity} elements. What is
   * approximate is who gets turned away. Claimants racing at the boundary can drive the count below
   * zero between them and all give their places back, so an admission can be rejected while the
   * queue is a place or two short of full. That only happens when it is already at the boundary,
   * where the caller is dropping work regardless.
   */
  private boolean claimPlace() {
    if (available.decrementAndGet() >= 0) {
      return true;
    }
    available.incrementAndGet();
    return false;
  }

  /**
   * Capacity claimed ahead of the element that will use it. Filling can only ever offer, because
   * the room was already taken; abandoning gives the room back.
   */
  private final class LinkedReservation implements Reservation<T> {
    private boolean done;

    @Override
    public void fill(T element) {
      if (element == null) {
        throw new NullPointerException("a queue cannot hold null");
      }
      if (!done) {
        done = true;
        queue.offer(element);
      }
    }

    @Override
    public void close() {
      if (!done) {
        done = true;
        available.incrementAndGet();
      }
    }
  }

  @Override
  Reservation<T> reserve() {
    return claimPlace() ? new LinkedReservation() : null;
  }

  @Override
  Object take() {
    return poll();
  }

  private Object poll() {
    Object element = queue.poll();
    if (element != null) {
      available.incrementAndGet();
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
    // Claimants at the boundary can transiently drive the count below zero before backing out.
    return Math.max(0, capacity - available.get());
  }
}

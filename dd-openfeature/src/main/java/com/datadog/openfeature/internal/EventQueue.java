package com.datadog.openfeature.internal;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

/**
 * A bounded, lock-free, multiple-producer single-consumer queue. Producers never block. A consumer
 * that finds the queue empty parks until the timeout, and the next producer to queue an element
 * wakes it.
 *
 * <p>Without that wake-up, the consumer slept in fixed 10 ms slices: at more than {@code capacity}
 * elements per 10 ms (410k a second for 4,096 slots), the queue filled during each sleep and
 * producers dropped elements, however idle the consumer was.
 *
 * @param <E> the element type.
 */
public final class EventQueue<E> {
  @SuppressWarnings("rawtypes")
  private static final AtomicReferenceFieldUpdater<EventQueue, Thread> WAITER =
      AtomicReferenceFieldUpdater.newUpdater(EventQueue.class, Thread.class, "waiter");

  private final ConcurrentLinkedQueue<E> queue = new ConcurrentLinkedQueue<>();
  private final AtomicInteger size = new AtomicInteger();
  private final int capacity;
  // The consumer while it is about to park or parked, so a producer can wake it.
  private volatile Thread waiter;

  public EventQueue(final int capacity) {
    this.capacity = capacity;
  }

  /**
   * Offers an element without blocking.
   *
   * @param element the element to offer.
   * @return {@code true} if the element was queued, {@code false} if the queue is full.
   */
  public boolean offer(final E element) {
    if (element == null) {
      throw new NullPointerException("element");
    }
    if (this.size.incrementAndGet() > this.capacity) {
      this.size.decrementAndGet();
      return false;
    }
    this.queue.offer(element);
    // One volatile read per offer. Only the producer that clears the waiter wakes it, so a burst
    // of producers costs one unpark.
    final Thread parked = this.waiter;
    if (parked != null && WAITER.compareAndSet(this, parked, null)) {
      LockSupport.unpark(parked);
    }
    return true;
  }

  /**
   * @return the head element, or {@code null} if the queue is empty.
   */
  public E poll() {
    final E element = this.queue.poll();
    if (element != null) {
      this.size.decrementAndGet();
    }
    return element;
  }

  /**
   * Polls the head element, waiting up to the given timeout for one to be available.
   *
   * @param timeout the maximum time to wait.
   * @param unit the timeout unit.
   * @return the head element, or {@code null} if none was available in time.
   * @throws InterruptedException if the consumer thread is interrupted while waiting.
   */
  public E poll(final long timeout, final TimeUnit unit) throws InterruptedException {
    final long deadline = System.nanoTime() + unit.toNanos(timeout);
    while (true) {
      final E element = poll();
      if (element != null) {
        return element;
      }
      final long remaining = deadline - System.nanoTime();
      if (remaining <= 0) {
        return null;
      }
      // Publish the waiter, then check again: a producer either sees the waiter and wakes it, or
      // queued its element before this check, which then finds it. No wake-up is lost.
      this.waiter = Thread.currentThread();
      final E queued = poll();
      if (queued != null) {
        this.waiter = null;
        return queued;
      }
      LockSupport.parkNanos(this, remaining);
      this.waiter = null;
      if (Thread.interrupted()) {
        throw new InterruptedException();
      }
    }
  }

  /**
   * Drains up to {@code limit} elements to the consumer.
   *
   * @param consumer the consumer of the drained elements.
   * @param limit the maximum number of elements to drain.
   */
  public void drain(final Consumer<E> consumer, final int limit) {
    for (int i = 0; i < limit; i++) {
      final E element = poll();
      if (element == null) {
        return;
      }
      consumer.accept(element);
    }
  }

  /**
   * @return the current number of queued elements.
   */
  public int size() {
    return this.size.get();
  }

  /**
   * @return the queue capacity.
   */
  public int capacity() {
    return this.capacity;
  }
}

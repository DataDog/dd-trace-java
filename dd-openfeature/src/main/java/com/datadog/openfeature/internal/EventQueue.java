package com.datadog.openfeature.internal;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

/**
 * A bounded, lock-free, multiple-producer single-consumer queue. Producers never block; the
 * consumer polls with a timeout by parking, as producers do not signal it.
 *
 * @param <E> the element type.
 */
public final class EventQueue<E> {
  private static final long PARK_NANOS = TimeUnit.MILLISECONDS.toNanos(10);

  private final ConcurrentLinkedQueue<E> queue = new ConcurrentLinkedQueue<>();
  private final AtomicInteger size = new AtomicInteger();
  private final int capacity;

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
    if (this.size.incrementAndGet() > this.capacity) {
      this.size.decrementAndGet();
      return false;
    }
    this.queue.offer(element);
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
      LockSupport.parkNanos(this, Math.min(remaining, PARK_NANOS));
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

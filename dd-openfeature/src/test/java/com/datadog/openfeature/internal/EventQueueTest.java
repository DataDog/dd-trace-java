package com.datadog.openfeature.internal;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class EventQueueTest {
  @Test
  void rejectsOffersBeyondCapacity() {
    final EventQueue<String> queue = new EventQueue<>(2);

    assertTrue(queue.offer("a"));
    assertTrue(queue.offer("b"));
    assertFalse(queue.offer("c"));

    assertEquals(2, queue.size());
    assertEquals(2, queue.capacity());
    assertEquals("a", queue.poll());
    assertTrue(queue.offer("c"));
  }

  @Test
  void pollsInFifoOrderAndReturnsNullWhenEmpty() {
    final EventQueue<Integer> queue = new EventQueue<>(4);
    queue.offer(1);
    queue.offer(2);

    assertEquals(1, queue.poll());
    assertEquals(2, queue.poll());
    assertNull(queue.poll());
    assertEquals(0, queue.size());
  }

  @Test
  void timedPollWaitsForAnElement() throws Exception {
    final EventQueue<String> queue = new EventQueue<>(1);
    final ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      executor.submit(
          () -> {
            MILLISECONDS.sleep(50);
            return queue.offer("late");
          });

      assertEquals("late", queue.poll(5, TimeUnit.SECONDS));
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void timedPollTimesOut() throws Exception {
    final EventQueue<String> queue = new EventQueue<>(1);
    final long start = System.nanoTime();

    assertNull(queue.poll(30, MILLISECONDS));

    assertTrue(System.nanoTime() - start >= MILLISECONDS.toNanos(30));
  }

  @Test
  void timedPollIsInterruptible() {
    final EventQueue<String> queue = new EventQueue<>(1);
    Thread.currentThread().interrupt();

    assertThrows(InterruptedException.class, () -> queue.poll(5, TimeUnit.SECONDS));
    assertFalse(Thread.currentThread().isInterrupted());
  }

  @Test
  void drainsUpToTheLimit() {
    final EventQueue<Integer> queue = new EventQueue<>(8);
    for (int i = 0; i < 5; i++) {
      queue.offer(i);
    }
    final List<Integer> drained = new ArrayList<>();

    queue.drain(drained::add, 3);

    assertEquals(List.of(0, 1, 2), drained);
    assertEquals(2, queue.size());
    queue.drain(drained::add, 10);
    assertEquals(List.of(0, 1, 2, 3, 4), drained);
  }

  @Test
  void concurrentProducersNeverExceedCapacity() throws Exception {
    final int capacity = 1000;
    final EventQueue<Integer> queue = new EventQueue<>(capacity);
    final int producers = 8;
    final ExecutorService executor = Executors.newFixedThreadPool(producers);
    final CountDownLatch start = new CountDownLatch(1);
    final AtomicInteger accepted = new AtomicInteger();
    try {
      for (int p = 0; p < producers; p++) {
        executor.submit(
            () -> {
              start.await();
              for (int i = 0; i < 500; i++) {
                if (queue.offer(i)) {
                  accepted.incrementAndGet();
                }
              }
              return null;
            });
      }
      start.countDown();
      executor.shutdown();
      assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
    } finally {
      executor.shutdownNow();
    }

    assertEquals(capacity, accepted.get());
    assertEquals(capacity, queue.size());
  }
}

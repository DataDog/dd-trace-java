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
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class EventQueueTest {
  @Test
  void rejectsNullWithoutConsumingCapacity() {
    final EventQueue<String> queue = new EventQueue<>(1);

    assertThrows(NullPointerException.class, () -> queue.offer(null));
    assertEquals(0, queue.size());
    assertTrue(queue.offer("a"));

    assertThrows(NullPointerException.class, () -> queue.offer(null));
    assertEquals(1, queue.size());
    assertEquals("a", queue.poll());
    assertEquals(0, queue.size());
  }

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
      executor.submit(() -> {
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
        executor.submit(() -> {
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

  @Test
  void findsAnElementBelowTheThresholdWithinOneSlice() throws Exception {
    final EventQueue<String> queue = new EventQueue<>(16);
    final ExecutorService consumer = Executors.newSingleThreadExecutor();
    try {
      final Future<String> polled = consumer.submit(() -> queue.poll(30, TimeUnit.SECONDS));
      MILLISECONDS.sleep(100); // let the consumer park
      assertTrue(queue.offer("one"));
      // No wake-up below the threshold: the consumer's next 10 ms slice finds it.
      assertEquals("one", polled.get(10, TimeUnit.SECONDS));
    } finally {
      consumer.shutdownNow();
    }
  }

  @Test
  void wakesAParkedConsumerOnlyOnceTheQueueReachesTheThreshold() throws Exception {
    // Slices as long as the timeout, so only a wake-up ends the park early.
    final EventQueue<String> queue = new EventQueue<>(16, 3, TimeUnit.SECONDS.toNanos(30));
    final ExecutorService consumer = Executors.newSingleThreadExecutor();
    try {
      final Future<String> polled = consumer.submit(() -> queue.poll(30, TimeUnit.SECONDS));
      MILLISECONDS.sleep(100); // let the consumer park
      assertTrue(queue.offer("a"));
      assertTrue(queue.offer("b"));
      assertThrows(TimeoutException.class, () -> polled.get(200, MILLISECONDS));

      assertTrue(queue.offer("c"));
      assertEquals("a", polled.get(10, TimeUnit.SECONDS));
    } finally {
      consumer.shutdownNow();
    }
  }

  @Test
  void losesNoWakeUpUnderContention() throws Exception {
    final EventQueue<Integer> queue = new EventQueue<>(64);
    final int producers = 4;
    final int perProducer = 20_000;
    final AtomicInteger queued = new AtomicInteger();
    final ExecutorService executor = Executors.newFixedThreadPool(producers);
    final CountDownLatch start = new CountDownLatch(1);
    try {
      for (int p = 0; p < producers; p++) {
        executor.execute(() -> {
          try {
            start.await();
          } catch (InterruptedException e) {
            return;
          }
          for (int i = 0; i < perProducer; i++) {
            while (!queue.offer(i)) {
              Thread.yield(); // full: retry, so every element is eventually queued
            }
            queued.incrementAndGet();
          }
        });
      }
      start.countDown();
      int consumed = 0;
      final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
      while (consumed < producers * perProducer) {
        // Each element must arrive long before this timeout; a lost wake-up would stall here.
        if (queue.poll(5, TimeUnit.SECONDS) != null) {
          consumed++;
        }
        assertTrue(System.nanoTime() < deadline, "stalled after " + consumed);
      }
      assertEquals(producers * perProducer, consumed);
      assertEquals(producers * perProducer, queued.get());
      assertNull(queue.poll());
    } finally {
      executor.shutdownNow();
    }
  }
}

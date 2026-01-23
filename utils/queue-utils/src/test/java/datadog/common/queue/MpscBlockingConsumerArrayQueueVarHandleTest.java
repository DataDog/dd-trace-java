package datadog.common.queue;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class MpscBlockingConsumerArrayQueueVarHandleTest
    extends AbstractQueueTest<MpscBlockingConsumerArrayQueueVarHandle<Integer>> {

  @Override
  MpscBlockingConsumerArrayQueueVarHandle<Integer> createQueue(int capacity) {
    return new MpscBlockingConsumerArrayQueueVarHandle<>(capacity);
  }

  @Test
  void drainShouldConsumeAllElementsInOrder() {
    queue.clear();
    for (int i = 1; i <= 5; i++) {
      queue.offer(i);
    }
    List<Integer> drained = new ArrayList<>();

    int count = queue.drain(drained::add);

    assertEquals(5, count);
    assertEquals(List.of(1, 2, 3, 4, 5), drained);
    assertTrue(queue.isEmpty());
  }

  @Test
  void drainWithLimitShouldConsumeOnlyLimitedNumber() {
    queue.clear();
    for (int i = 1; i <= 6; i++) {
      queue.offer(i);
    }
    List<Integer> drained = new ArrayList<>();

    int count = queue.drain(drained::add, 3);

    assertEquals(3, count);
    assertEquals(List.of(1, 2, 3), drained);
    assertEquals(3, queue.size());
  }

  @Test
  @Timeout(10)
  void multipleProducersSingleConsumerShouldConsumeAllElementsWithoutDuplicates()
      throws InterruptedException {
    int total = 1000;
    int producers = 4;
    MpscBlockingConsumerArrayQueueVarHandle<Integer> queue =
        new MpscBlockingConsumerArrayQueueVarHandle<>(1024);
    List<Integer> results = Collections.synchronizedList(new ArrayList<>());
    CountDownLatch latch = new CountDownLatch(producers);

    // Multiple producers
    for (int id = 1; id <= producers; id++) {
      final int producerId = id;
      Thread producer =
          new Thread(
              () -> {
                for (int i = 0; i < total / producers; i++) {
                  int val = producerId * 10_000 + i;
                  while (!queue.offer(val)) {
                    Thread.yield();
                  }
                }
                latch.countDown();
              });
      producer.start();
    }

    // Single consumer
    Thread consumer =
        new Thread(
            () -> {
              while (results.size() < total) {
                Integer v = queue.poll();
                if (v != null) {
                  results.add(v);
                } else {
                  Thread.yield();
                }
              }
            });
    consumer.start();

    latch.await();
    consumer.join();

    assertEquals(total, results.size());
    assertEquals(total, results.stream().distinct().count()); // all unique
  }

  @Test
  void blockingTakeShouldWakeUpWhenProducerOffers() throws InterruptedException {
    MpscBlockingConsumerArrayQueueVarHandle<Integer> queue =
        new MpscBlockingConsumerArrayQueueVarHandle<>(4);
    AtomicReference<Integer> result = new AtomicReference<>();

    Thread consumer =
        new Thread(
            () -> {
              try {
                result.set(queue.take());
              } catch (InterruptedException ignored) {
              }
            });
    consumer.start();
    Thread.sleep(100);
    queue.offer(123);
    consumer.join(1000);

    assertEquals(123, result.get());
    assertTrue(queue.isEmpty());
  }

  @Test
  void fillInsertsUpToCapacity() {
    int[] counter = {0};
    int filled = queue.fill(() -> counter[0] < 10 ? counter[0]++ : null, 10);

    assertEquals(8, filled);
    assertEquals(8, queue.size());
  }

  @Test
  void pollWithTimeoutReturnsNullIfNoElementBecomesAvailable() throws InterruptedException {
    long start = System.nanoTime();
    Integer value = queue.poll(200, TimeUnit.MILLISECONDS);
    long elapsedMs = NANOSECONDS.toMillis(System.nanoTime() - start);

    assertNull(value);
    assertTrue(elapsedMs >= 200); // waited approximately the timeout
  }

  @Test
  void pollWithZeroTimeoutBehavesLikeImmediatePoll() throws InterruptedException {
    assertNull(queue.poll(0, TimeUnit.MILLISECONDS));

    queue.offer(99);

    assertEquals(99, queue.poll(0, TimeUnit.MILLISECONDS));
  }

  @Test
  void pollThrowsInterruptedExceptionIfInterrupted() throws InterruptedException {
    AtomicBoolean thrown = new AtomicBoolean();
    Thread thread =
        new Thread(
            () -> {
              try {
                queue.poll(500, TimeUnit.MILLISECONDS);
              } catch (InterruptedException ie) {
                thrown.set(true);
                Thread.currentThread().interrupt();
              }
            });
    thread.start();

    Thread.sleep(50);
    thread.interrupt();
    thread.join();

    assertTrue(thrown.get());
  }
}

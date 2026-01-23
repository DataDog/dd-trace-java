package datadog.common.queue;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class MpscArrayQueueVarHandleTest extends AbstractQueueTest<MpscArrayQueueVarHandle<Integer>> {

  @Test
  @Timeout(10)
  void multipleProducersSingleConsumerShouldConsumeAllElementsWithoutDuplicationOrLoss()
      throws InterruptedException {
    int total = 1000;
    int producers = 4;
    MpscArrayQueueVarHandle<Integer> queue = new MpscArrayQueueVarHandle<>(1024);
    List<Integer> results = Collections.synchronizedList(new ArrayList<>());
    ExecutorService executor = Executors.newFixedThreadPool(producers);
    CountDownLatch latch = new CountDownLatch(producers);
    CountDownLatch consumerDone = new CountDownLatch(1);

    // Multiple producers enqueue concurrently
    for (int id = 1; id <= producers; id++) {
      final int producerId = id;
      executor.submit(
          () -> {
            for (int i = 0; i < total / producers; i++) {
              int value = (producerId * 10000) + i;
              while (!queue.offer(value)) {
                Thread.yield();
              }
            }
            latch.countDown();
          });
    }

    // A single consumer drains all elements
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
              consumerDone.countDown();
            });
    consumer.start();

    latch.await();
    consumerDone.await();
    executor.shutdown();

    assertEquals(total, results.size());
    assertEquals(total, results.stream().distinct().count()); // all unique
  }

  @Override
  MpscArrayQueueVarHandle<Integer> createQueue(int capacity) {
    return new MpscArrayQueueVarHandle<>(capacity);
  }
}

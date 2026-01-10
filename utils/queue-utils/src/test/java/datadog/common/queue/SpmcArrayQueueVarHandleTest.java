package datadog.common.queue;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class SpmcArrayQueueVarHandleTest extends AbstractQueueTest<SpmcArrayQueueVarHandle<Integer>> {

  @Override
  SpmcArrayQueueVarHandle<Integer> createQueue(int capacity) {
    return new SpmcArrayQueueVarHandle<>(capacity);
  }

  @Test
  @Timeout(10)
  void singleProducerMultipleConsumersShouldConsumeAllElementsWithoutDuplicationOrLoss()
      throws InterruptedException {
    int total = 1000;
    int consumers = 4;
    SpmcArrayQueueVarHandle<Integer> queue = new SpmcArrayQueueVarHandle<>(1024);
    List<Integer> results = Collections.synchronizedList(new ArrayList<>());
    ExecutorService executor = Executors.newFixedThreadPool(consumers);
    CountDownLatch latch = new CountDownLatch(consumers);

    // One producer fills the queue
    Thread producer =
        new Thread(
            () -> {
              for (int i = 0; i < total; i++) {
                while (!queue.offer(i)) {
                  Thread.yield();
                }
              }
            });
    producer.start();

    // Multiple consumers drain concurrently
    for (int i = 0; i < consumers; i++) {
      executor.submit(
          () -> {
            while (results.size() < total) {
              Integer v = queue.poll();
              if (v != null) {
                results.add(v);
              } else {
                Thread.yield();
              }
            }
            latch.countDown();
          });
    }

    latch.await();
    producer.join();
    executor.shutdown();

    assertEquals(total, results.size());
    assertEquals(total, results.stream().distinct().count()); // no duplicates
    List<Integer> expected = IntStream.range(0, total).boxed().collect(Collectors.toList());
    assertTrue(results.containsAll(expected)); // all items consumed
  }
}

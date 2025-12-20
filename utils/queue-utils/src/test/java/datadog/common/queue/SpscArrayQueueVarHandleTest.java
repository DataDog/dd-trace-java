package datadog.common.queue;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SpscArrayQueueVarHandleTest extends AbstractQueueTest<SpscArrayQueueVarHandle<Integer>> {

  @Test
  void singleProducerSingleConsumerConcurrency() throws InterruptedException {
    SpscArrayQueueVarHandle<Integer> queue = new SpscArrayQueueVarHandle<>(1024);
    int producerCount = 1000;
    AtomicInteger consumed = new AtomicInteger(0);
    List<Integer> consumedValues = Collections.synchronizedList(new ArrayList<>());

    Thread producer =
        new Thread(
            () -> {
              for (int i = 1; i <= producerCount; i++) {
                queue.offer(i);
              }
            });

    Thread consumer =
        new Thread(
            () -> {
              while (consumed.get() < producerCount) {
                Integer v = queue.poll();
                if (v != null) {
                  consumedValues.add(v);
                  consumed.incrementAndGet();
                }
              }
            });

    producer.start();
    consumer.start();

    producer.join();
    consumer.join();

    assertEquals(producerCount, consumed.get());
    assertEquals(producerCount, consumedValues.stream().distinct().count());
  }

  @Override
  SpscArrayQueueVarHandle<Integer> createQueue(int capacity) {
    return new SpscArrayQueueVarHandle<>(capacity);
  }
}

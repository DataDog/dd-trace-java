package datadog.common.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import org.junit.jupiter.api.Test;

/**
 * Contention tests for the MPSC backing, where the admission contract actually has to hold: many
 * producers claiming slots against a single consumer freeing them.
 *
 * <p>What is being checked is conservation. Every element a producer was told it admitted must
 * reach the consumer exactly once, and every element it was told was rejected must be counted as
 * dropped — so admitted plus dropped accounts for everything offered, with nothing lost, duplicated
 * or invented in between.
 */
class MpscQueueStressTest {

  private static final int PRODUCERS = 8;
  private static final int PER_PRODUCER = 20_000;
  private static final int TOTAL = PRODUCERS * PER_PRODUCER;
  private static final int CAPACITY = 128;
  private static final long TIMEOUT_SECONDS = 60;

  @Test
  void conservesEveryElementUnderContention() throws Exception {
    Queue<Integer> queue = Queues.mpscQueue(CAPACITY);
    AtomicIntegerArray timesSeen = new AtomicIntegerArray(TOTAL);
    AtomicInteger admitted = new AtomicInteger();
    AtomicInteger consumed = new AtomicInteger();

    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch producersDone = new CountDownLatch(PRODUCERS);

    for (int p = 0; p < PRODUCERS; p++) {
      final int producer = p;
      Thread thread =
          new Thread(
              () -> {
                await(start);
                try {
                  for (int i = 0; i < PER_PRODUCER; i++) {
                    int value = producer * PER_PRODUCER + i;
                    if (queue.tryPut(value)) {
                      admitted.incrementAndGet();
                    }
                  }
                } finally {
                  producersDone.countDown();
                }
              },
              "producer-" + p);
      thread.setDaemon(true);
      thread.start();
    }

    AtomicBoolean consumerFailed = new AtomicBoolean();
    Thread consumer =
        new Thread(
            () -> {
              boolean producersFinished = false;
              while (true) {
                boolean hadWork = queue.process(value -> timesSeen.incrementAndGet(value));
                if (hadWork) {
                  consumed.incrementAndGet();
                } else if (producersFinished) {
                  return;
                } else {
                  producersFinished = producersDone.getCount() == 0;
                  Thread.yield();
                }
              }
            },
            "consumer");
    consumer.setDaemon(true);
    consumer.setUncaughtExceptionHandler((t, e) -> consumerFailed.set(true));
    consumer.start();

    start.countDown();
    assertTrue(
        producersDone.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "producers did not finish in time");
    consumer.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
    assertFalse(consumer.isAlive(), "consumer did not finish in time");
    assertFalse(consumerFailed.get(), "consumer thread threw");

    assertEquals(
        admitted.get(), consumed.get(), "every admitted element reaches the consumer once");
    assertEquals(TOTAL - admitted.get(), queue.dropped(), "every rejection is counted");
    assertEquals(0, queue.size());

    for (int value = 0; value < TOTAL; value++) {
      int seen = timesSeen.get(value);
      assertTrue(seen <= 1, "element " + value + " was consumed " + seen + " times");
    }
  }

  /**
   * The reserve-before-construct guarantee under contention: producers race for a capacity that is
   * never freed, so no producer may ever run.
   */
  @Test
  void neverInvokesProducerWhileFull() throws Exception {
    Queue<Integer> queue = Queues.mpscQueue(CAPACITY);
    while (queue.tryPut(0)) {
      // fill it, and leave it full — nothing consumes
    }
    // the loop above ends on a rejection, which is itself a drop
    long droppedWhileFilling = queue.dropped();

    AtomicInteger produced = new AtomicInteger();
    AtomicInteger admittedAfterFull = new AtomicInteger();
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(PRODUCERS);

    for (int p = 0; p < PRODUCERS; p++) {
      Thread thread =
          new Thread(
              () -> {
                await(start);
                try {
                  for (int i = 0; i < PER_PRODUCER; i++) {
                    boolean landed =
                        queue.tryPut(
                            produced,
                            counter -> {
                              counter.incrementAndGet();
                              return 1;
                            });
                    if (landed) {
                      admittedAfterFull.incrementAndGet();
                    }
                  }
                } finally {
                  done.countDown();
                }
              },
              "producer-" + p);
      thread.setDaemon(true);
      thread.start();
    }

    start.countDown();
    assertTrue(done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "producers did not finish in time");

    assertEquals(0, admittedAfterFull.get(), "a full queue admits nothing");
    assertEquals(0, produced.get(), "no element may be built for a slot that was never claimed");
    assertEquals(
        droppedWhileFilling + (long) PRODUCERS * PER_PRODUCER,
        queue.dropped(),
        "every rejected admission is counted");
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}

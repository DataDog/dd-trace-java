package datadog.trace.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class AccumulatorTest {

  enum Counters {
    FOO,
    BAR,
    BAZ
  }

  @Test
  void freshAccumulatorSumsToZero() {
    long[][] data = Accumulator.create(Counters.values());
    long[] drained = Accumulator.accumulateAndReset(data);
    for (Counters c : Counters.values()) {
      assertEquals(0L, drained[c.ordinal()]);
    }
  }

  @Test
  void incIncrementsByOne() {
    long[][] data = Accumulator.create(Counters.values());
    Accumulator.inc(data, Counters.FOO);
    Accumulator.inc(data, Counters.FOO);
    Accumulator.inc(data, Counters.BAR);

    long[] drained = Accumulator.accumulateAndReset(data);
    assertEquals(2L, drained[Counters.FOO.ordinal()]);
    assertEquals(1L, drained[Counters.BAR.ordinal()]);
    assertEquals(0L, drained[Counters.BAZ.ordinal()]);
  }

  @Test
  void addAppliesArbitraryDelta() {
    long[][] data = Accumulator.create(Counters.values());
    Accumulator.add(data, Counters.BAZ, 41L);
    Accumulator.add(data, Counters.BAZ, 1L);

    long[] drained = Accumulator.accumulateAndReset(data);
    assertEquals(42L, drained[Counters.BAZ.ordinal()]);
  }

  @Test
  void updateAppliesSeveralOpsUnderOneLock() {
    long[][] data = Accumulator.create(Counters.values());
    Accumulator.update(
        data,
        stripe -> {
          Accumulator.inc(stripe, Counters.FOO);
          Accumulator.inc(stripe, Counters.FOO);
          Accumulator.add(stripe, Counters.BAR, 5L);
        });

    long[] drained = Accumulator.accumulateAndReset(data);
    assertEquals(2L, drained[Counters.FOO.ordinal()]);
    assertEquals(5L, drained[Counters.BAR.ordinal()]);
  }

  @Test
  void accumulateAndResetsSoASecondDrainIsZero() {
    long[][] data = Accumulator.create(Counters.values());
    Accumulator.inc(data, Counters.FOO);

    long[] first = Accumulator.accumulateAndReset(data);
    assertEquals(1L, first[Counters.FOO.ordinal()]);

    long[] second = Accumulator.accumulateAndReset(data);
    for (Counters c : Counters.values()) {
      assertEquals(0L, second[c.ordinal()]);
    }
  }

  @Test
  void drainedRowsAreAllTheSameLength() {
    long[][] data = Accumulator.create(Counters.values());
    long[] drained = Accumulator.accumulateAndReset(data);
    assertEquals(data[0].length, drained.length);
    assertTrue(drained.length >= Counters.values().length);
  }

  @Test
  void concurrentIncrementsAreNotLost() throws InterruptedException {
    long[][] data = Accumulator.create(Counters.values());
    int threadCount = 16;
    int incrementsPerThread = 10_000;

    ExecutorService pool = Executors.newFixedThreadPool(threadCount);
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(threadCount);
    try {
      for (int t = 0; t < threadCount; t++) {
        pool.execute(
            () -> {
              try {
                start.await();
                for (int i = 0; i < incrementsPerThread; i++) {
                  Accumulator.inc(data, Counters.FOO);
                }
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              } finally {
                done.countDown();
              }
            });
      }
      start.countDown();
      assertTrue(done.await(30, TimeUnit.SECONDS));
    } finally {
      pool.shutdown();
    }

    long[] drained = Accumulator.accumulateAndReset(data);
    assertEquals((long) threadCount * incrementsPerThread, drained[Counters.FOO.ordinal()]);
  }

  @Test
  void concurrentAccumulateAndDuringWritesNeverExceedsWritten() throws InterruptedException {
    long[][] data = Accumulator.create(Counters.values());
    int threadCount = 8;
    int incrementsPerThread = 5_000;

    ExecutorService pool = Executors.newFixedThreadPool(threadCount + 1);
    CountDownLatch done = new CountDownLatch(threadCount);
    AtomicBoolean stop = new AtomicBoolean(false);
    long[] runningTotal = {0L};

    try {
      pool.execute(
          () -> {
            while (!stop.get()) {
              long[] drained = Accumulator.accumulateAndReset(data);
              synchronized (runningTotal) {
                runningTotal[0] += drained[Counters.FOO.ordinal()];
              }
            }
          });

      for (int t = 0; t < threadCount; t++) {
        pool.execute(
            () -> {
              for (int i = 0; i < incrementsPerThread; i++) {
                Accumulator.inc(data, Counters.FOO);
              }
              done.countDown();
            });
      }

      assertTrue(done.await(30, TimeUnit.SECONDS));
      stop.set(true);
      long[] finalDrain = Accumulator.accumulateAndReset(data);
      synchronized (runningTotal) {
        runningTotal[0] += finalDrain[Counters.FOO.ordinal()];
      }

      assertEquals((long) threadCount * incrementsPerThread, runningTotal[0]);
    } finally {
      pool.shutdown();
    }
  }
}

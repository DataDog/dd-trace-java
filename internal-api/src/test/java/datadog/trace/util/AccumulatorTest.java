package datadog.trace.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
    Accumulator<Counters> counters = Accumulator.of(Counters.values());
    Accumulator.Counts<Counters> drained = counters.accumulateAndReset();
    for (Counters c : Counters.values()) {
      assertEquals(0L, drained.get(c));
    }
  }

  @Test
  void incIncrementsByOne() {
    Accumulator<Counters> counters = Accumulator.of(Counters.values());
    counters.inc(Counters.FOO);
    counters.inc(Counters.FOO);
    counters.inc(Counters.BAR);

    Accumulator.Counts<Counters> drained = counters.accumulateAndReset();
    assertEquals(2L, drained.get(Counters.FOO));
    assertEquals(1L, drained.get(Counters.BAR));
    assertEquals(0L, drained.get(Counters.BAZ));
  }

  @Test
  void addAppliesArbitraryDelta() {
    Accumulator<Counters> counters = Accumulator.of(Counters.values());
    counters.add(Counters.BAZ, 41L);
    counters.add(Counters.BAZ, 1L);

    Accumulator.Counts<Counters> drained = counters.accumulateAndReset();
    assertEquals(42L, drained.get(Counters.BAZ));
  }

  @Test
  void accumulateAndResetsSoASecondDrainIsZero() {
    Accumulator<Counters> counters = Accumulator.of(Counters.values());
    counters.inc(Counters.FOO);

    Accumulator.Counts<Counters> first = counters.accumulateAndReset();
    assertEquals(1L, first.get(Counters.FOO));

    Accumulator.Counts<Counters> second = counters.accumulateAndReset();
    for (Counters c : Counters.values()) {
      assertEquals(0L, second.get(c));
    }
  }

  @Test
  void concurrentIncrementsAreNotLost() throws InterruptedException {
    Accumulator<Counters> counters = Accumulator.of(Counters.values());
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
                  counters.inc(Counters.FOO);
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

    Accumulator.Counts<Counters> drained = counters.accumulateAndReset();
    assertEquals((long) threadCount * incrementsPerThread, drained.get(Counters.FOO));
  }

  @Test
  void concurrentAccumulateAndDuringWritesNeverExceedsWritten()
      throws InterruptedException, ExecutionException, TimeoutException {
    Accumulator<Counters> counters = Accumulator.of(Counters.values());
    int threadCount = 8;
    int incrementsPerThread = 5_000;

    ExecutorService pool = Executors.newFixedThreadPool(threadCount + 1);
    CountDownLatch done = new CountDownLatch(threadCount);
    AtomicBoolean stop = new AtomicBoolean(false);
    long[] runningTotal = {0L};

    try {
      Future<?> drainer =
          pool.submit(
              () -> {
                while (!stop.get()) {
                  Accumulator.Counts<Counters> drained = counters.accumulateAndReset();
                  synchronized (runningTotal) {
                    runningTotal[0] += drained.get(Counters.FOO);
                  }
                }
              });

      for (int t = 0; t < threadCount; t++) {
        pool.execute(
            () -> {
              for (int i = 0; i < incrementsPerThread; i++) {
                counters.inc(Counters.FOO);
              }
              done.countDown();
            });
      }

      assertTrue(done.await(30, TimeUnit.SECONDS));
      stop.set(true);
      drainer.get(30, TimeUnit.SECONDS);

      Accumulator.Counts<Counters> finalDrain = counters.accumulateAndReset();
      synchronized (runningTotal) {
        runningTotal[0] += finalDrain.get(Counters.FOO);
      }

      assertEquals((long) threadCount * incrementsPerThread, runningTotal[0]);
    } finally {
      pool.shutdown();
    }
  }

  @Test
  void sumDoesNotResetStripes() {
    Accumulator<Counters> counters = Accumulator.of(Counters.values());
    counters.inc(Counters.FOO);

    Accumulator.Counts<Counters> first = counters.sum();
    assertEquals(1L, first.get(Counters.FOO));

    // sum() didn't reset anything, so a second sum() sees the same total
    Accumulator.Counts<Counters> second = counters.sum();
    assertEquals(1L, second.get(Counters.FOO));

    // and a real drain afterwards still sees the value sum() didn't consume
    Accumulator.Counts<Counters> drained = counters.accumulateAndReset();
    assertEquals(1L, drained.get(Counters.FOO));
  }

  @Test
  void sumReflectsIncrementsMadeAfterAnEarlierSum() {
    Accumulator<Counters> counters = Accumulator.of(Counters.values());
    counters.inc(Counters.FOO);
    counters.sum();

    counters.inc(Counters.FOO);
    Accumulator.Counts<Counters> second = counters.sum();
    assertEquals(2L, second.get(Counters.FOO));
  }

  @Test
  void zeroSeedsAnAllZeroCountsWithoutAScratchAccumulator() {
    Accumulator.Counts<Counters> zero = Accumulator.Counts.zero(Counters.values());
    assertEquals(0L, zero.get(Counters.FOO));
    assertEquals(0L, zero.get(Counters.BAR));

    Accumulator<Counters> counters = Accumulator.of(Counters.values());
    counters.inc(Counters.FOO);

    Accumulator.Counts<Counters> live = zero.plus(counters.sum());
    assertEquals(1L, live.get(Counters.FOO));
  }

  @Test
  void ofAndZeroAcceptAnEnumClassInsteadOfAValuesArray() {
    Accumulator<Counters> counters = Accumulator.of(Counters.class);
    counters.inc(Counters.FOO);

    Accumulator.Counts<Counters> zero = Accumulator.Counts.zero(Counters.class);
    Accumulator.Counts<Counters> live = zero.plus(counters.sum());
    assertEquals(1L, live.get(Counters.FOO));
  }

  @Test
  void countsExposesItsOwnKeysWithoutASeparateValuesArray() {
    Accumulator<Counters> counters = Accumulator.of(Counters.values());
    counters.inc(Counters.FOO);
    counters.add(Counters.BAR, 5L);

    Accumulator.Counts<Counters> drained = counters.accumulateAndReset();
    assertEquals(Counters.values().length, drained.keys().length);

    long total = 0L;
    for (Counters c : drained.keys()) {
      total += drained.get(c);
    }
    assertEquals(6L, total);
  }

  @Test
  void plusCombinesAStoredRunningTotalWithALiveSumWithoutMutatingEither() {
    Accumulator<Counters> counters = Accumulator.of(Counters.values());
    counters.inc(Counters.FOO);
    counters.add(Counters.BAR, 5L);

    // drain once, e.g. as if a reporting cycle already ran and stored this total
    Accumulator.Counts<Counters> storedTotal = counters.accumulateAndReset();

    // more activity happens after that drain, before the next one
    counters.inc(Counters.FOO);

    Accumulator.Counts<Counters> live = storedTotal.plus(counters.sum());
    assertEquals(2L, live.get(Counters.FOO));
    assertEquals(5L, live.get(Counters.BAR));

    // neither input was mutated by combining them
    assertEquals(1L, storedTotal.get(Counters.FOO));
    assertEquals(1L, counters.sum().get(Counters.FOO));
  }
}

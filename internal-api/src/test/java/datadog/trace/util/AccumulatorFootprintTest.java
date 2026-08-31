package datadog.trace.util;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import datadog.environment.JavaVirtualMachine;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openjdk.jol.info.GraphLayout;

/**
 * Retained-footprint comparison (JOL) for {@link Accumulator} vs the alternative it actually
 * displaces: one {@code LongAdder} per counter (there is no multi-counter {@code LongAdder} -- a
 * caller wanting N additive counters allocates N of them, one field each, as {@code OtlpTelemetry}
 * and {@code PayloadDispatcherImpl} do today).
 *
 * <p>A freshly constructed {@code LongAdder} is nearly free -- it holds no {@code Cell[]} table
 * until contention forces one -- so comparing fresh instances understates its real cost and
 * flatters {@code LongAdder}. {@link Accumulator} pays its full striped array up front, at
 * creation, sized for {@link Runtime#availableProcessors()} regardless of whether contention ever
 * materializes. The realistic comparison is therefore not fresh-vs-fresh but contended-vs-fresh:
 * what each actually costs once the counters they represent are hit by real concurrent writers, as
 * they are on the telemetry paths this class targets.
 *
 * <p>Measured on a 10-CPU machine (JDK 1.8.0_382 Zulu), 4 counters, {@code
 * Accumulator.stripeCount()} = 16:
 *
 * <pre>{@code
 * fresh:      4 LongAdders =    160 bytes, Accumulator = 2384 bytes
 * contended:  4 LongAdders =  17560 bytes, Accumulator = 2384 bytes
 * }</pre>
 *
 * Finding: fresh, {@code LongAdder} looks ~15x lighter -- but that's an artifact of never having
 * been written to concurrently. Once real contention forces each {@code LongAdder}'s {@code Cell[]}
 * table to grow (each {@code Cell} is {@code @Contended}-padded against false sharing, the same
 * problem {@link Accumulator}'s own padding solves), the four {@code LongAdder}s alone end up over
 * 7x heavier than {@code Accumulator}'s entire fixed footprint -- and {@code Accumulator} does not
 * grow further as more contention arrives within its existing stripe count, while every additional
 * concurrently-written {@code LongAdder} keeps paying this cost independently. {@code
 * Accumulator}'s up-front cost is the more predictable one: fixed at creation, independent of
 * runtime contention, and shared (one striped array) across however many counters the caller's enum
 * declares, rather than paid per counter.
 */
class AccumulatorFootprintTest {

  enum Counters {
    REQUESTS,
    ERRORS,
    RETRIES,
    BYTES_SENT
  }

  @BeforeAll
  static void assumeNotJ9Jvm() {
    // JOL's GraphLayout relies on HotSpot-specific Unsafe internals and throws
    // IllegalStateException on J9-based JVMs (IBM/Semeru) -- same guard as
    // StringIndexFootprintTest / ScopeAndContinuationLayoutTest.
    assumeFalse(JavaVirtualMachine.isJ9());
  }

  static long bytes(Object root) {
    return GraphLayout.parseInstance(root).totalSize();
  }

  static LongAdder[] freshAdders() {
    LongAdder[] adders = new LongAdder[Counters.values().length];
    for (int i = 0; i < adders.length; i++) {
      adders[i] = new LongAdder();
    }
    return adders;
  }

  @Test
  void freshFootprint() {
    LongAdder[] adders = freshAdders();
    long[][] accumulator = Accumulator.create(Counters.values());

    long adderBytes = bytes((Object) adders);
    long accumulatorBytes = bytes(accumulator);

    System.out.printf(
        "fresh:      %d LongAdders = %6d bytes, Accumulator = %6d bytes%n",
        adders.length, adderBytes, accumulatorBytes);
  }

  /**
   * Drives real multi-threaded contention against a fresh set of {@code LongAdder}s to force their
   * {@code Cell[]} tables to grow, then compares against {@link Accumulator}'s fixed footprint --
   * the realistic comparison, since production callers write to these counters concurrently rather
   * than leaving them untouched.
   *
   * <p>Cell-table growth is driven by JVM-internal CAS-collision detection, not something this test
   * controls directly, so the exact grown size can vary by run/JVM; the one invariant asserted is
   * monotonic growth (a contended footprint can only be at least the fresh one).
   */
  @Test
  void contendedFootprint() throws InterruptedException {
    LongAdder[] adders = freshAdders();
    long freshAdderBytes = bytes((Object) adders);

    int threads = Math.max(4, Runtime.getRuntime().availableProcessors());
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(threads);
    try {
      for (int t = 0; t < threads; t++) {
        pool.execute(
            () -> {
              try {
                start.await();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (System.nanoTime() < deadline) {
                  for (LongAdder adder : adders) {
                    adder.increment();
                  }
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

    long contendedAdderBytes = bytes((Object) adders);
    long[][] accumulator = Accumulator.create(Counters.values());
    long accumulatorBytes = bytes(accumulator);

    System.out.printf(
        "contended:  %d LongAdders = %6d bytes, Accumulator = %6d bytes%n",
        adders.length, contendedAdderBytes, accumulatorBytes);

    assertTrue(
        contendedAdderBytes >= freshAdderBytes,
        "contended LongAdder footprint should never shrink below the fresh footprint");
  }
}

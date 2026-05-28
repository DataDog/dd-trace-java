package datadog.trace.util.concurrent;

import static java.util.concurrent.TimeUnit.SECONDS;

import java.util.function.BiConsumer;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Group;
import org.openjdk.jmh.annotations.GroupThreads;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Throughput benchmarks for {@link MpscRingBuffer}.
 *
 * <ul>
 *   <li>{@code write_1p / write_8p / write_16p} — producer-side throughput with a background
 *       drainer consuming what's published. Measures the cost of one {@code tryWrite} including CAS
 *       contention on the producer cursor at the given thread count.
 *   <li>{@code e2e_8p} — JMH {@code @Group} pairing 8 producers with 1 consumer. Aggregate
 *       throughput reflects whichever side is the bottleneck under realistic pressure.
 * </ul>
 *
 * <p>Run with {@code -p capacity=...} to override the default ring capacity.
 */
@State(Scope.Benchmark)
@Warmup(iterations = 2, time = 15, timeUnit = SECONDS)
@Measurement(iterations = 5, time = 15, timeUnit = SECONDS)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(SECONDS)
@Fork(value = 1)
public class MpscRingBufferBenchmark {

  /**
   * Static filler so the lambda is non-capturing and the JIT can hoist it past the {@code tryWrite}
   * call. Context arg comes first, slot last — matches {@code TagMap.forEach} convention.
   */
  private static final BiConsumer<Long, Slot> FILLER = (v, slot) -> slot.value = v;

  /** Mutable slot. Replicates the per-publish allocation a real producer wants to avoid. */
  public static final class Slot {
    long value;
  }

  @Param({"1024"})
  public int capacity;

  /**
   * Shared ring for the {@code write_*} benches. A background drainer keeps space available so
   * producer benchmarks measure write throughput rather than full-ring drop throughput.
   */
  MpscRingBuffer<Slot> ring;

  private volatile boolean stopDrainer;
  private Thread drainerThread;

  /**
   * Separate ring for the {@code e2e_*} group benches. JMH drives both sides directly so we don't
   * want our own background drainer for those.
   */
  MpscRingBuffer<Slot> e2eRing;

  @Setup(Level.Trial)
  public void setup() {
    ring = new MpscRingBuffer<>(Slot::new, capacity);
    e2eRing = new MpscRingBuffer<>(Slot::new, capacity);
    stopDrainer = false;
    drainerThread =
        new Thread(
            () -> {
              while (!stopDrainer) {
                if (ring.drain((Slot s) -> {}) == 0) Thread.yield();
              }
            },
            "MpscRingBufferBenchmark-drainer");
    drainerThread.setDaemon(true);
    drainerThread.start();
  }

  @TearDown(Level.Trial)
  public void teardown() throws InterruptedException {
    stopDrainer = true;
    drainerThread.join(5_000);
  }

  @State(Scope.Thread)
  public static class ThreadState {
    long counter;
  }

  // ============ Write throughput with background drainer ============

  @Threads(1)
  @Benchmark
  public boolean write_1p(ThreadState ts) {
    return ring.tryWrite(ts.counter++, FILLER);
  }

  @Threads(8)
  @Benchmark
  public boolean write_8p(ThreadState ts) {
    return ring.tryWrite(ts.counter++, FILLER);
  }

  @Threads(16)
  @Benchmark
  public boolean write_16p(ThreadState ts) {
    return ring.tryWrite(ts.counter++, FILLER);
  }

  // ============ End-to-end producer/consumer pairing ============
  //
  // JMH runs both methods in the same trial: 8 producer threads + 1 consumer thread. Throughput
  // is reported as ops/sec aggregated across all 9 threads, but the consumer's drain count
  // dwarfs the producer ops since one call processes many slots -- in practice the bottleneck
  // is the producer side (CAS contention), and that's what the number reflects.

  private static final BiConsumer<Blackhole, Slot> CONSUMER = (bh, slot) -> bh.consume(slot.value);

  @Group("e2e_8p")
  @GroupThreads(8)
  @Benchmark
  public boolean e2e_producer(ThreadState ts) {
    return e2eRing.tryWrite(ts.counter++, FILLER);
  }

  @Group("e2e_8p")
  @GroupThreads(1)
  @Benchmark
  public int e2e_consumer(Blackhole bh) {
    int drained = e2eRing.drain(bh, CONSUMER);
    if (drained == 0) Thread.yield();
    return drained;
  }
}

package datadog.trace.util.concurrent;

import static java.util.concurrent.TimeUnit.SECONDS;

import java.util.function.BiConsumer;
import org.jctools.queues.MpscArrayQueue;
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
 * Head-to-head comparison of {@link MpscRingBuffer} (mutable pre-allocated slots) against the
 * conventional approach of a jctools {@link MpscArrayQueue} with a fresh {@code Slot} allocated per
 * publish. The latter is the pattern the current CSS code uses for {@code SpanSnapshot} on the
 * producer side, so the delta between the two measures the actual allocation/handoff saving of the
 * ring-buffer rewrite.
 *
 * <ul>
 *   <li>{@code write_*_8p} — 8 producers, background drainer keeps the structure empty so the
 *       measurement reflects publish cost, not full-structure drop cost. Pair-compare ring vs queue
 *       at matched capacity.
 *   <li>{@code e2e_*_8p} — JMH {@code @Group} pairing 8 producers with 1 consumer for each
 *       structure. End-to-end ops/s under realistic backpressure.
 * </ul>
 *
 * <p>Run with {@code -prof gc} to also see per-op allocation rate — that's where the ring's win is
 * loudest, since the queue allocates one {@code Slot} per publish and the ring allocates none.
 */
@State(Scope.Benchmark)
@Warmup(iterations = 2, time = 15, timeUnit = SECONDS)
@Measurement(iterations = 5, time = 15, timeUnit = SECONDS)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(SECONDS)
@Fork(value = 1)
public class RingVsQueueBenchmark {

  /**
   * Shared slot type. {@code MpscRingBuffer} pre-allocates these and the producer mutates in place;
   * the queue path allocates a fresh one per publish and offers the reference. Two constructors so
   * both styles read naturally.
   */
  public static final class Slot {
    long value;

    Slot() {}

    Slot(final long value) {
      this.value = value;
    }
  }

  // Static (non-capturing) handlers. Passing ts/bh as context lets the JIT keep these as
  // singleton functions and avoid per-call lambda allocation.
  private static final BiConsumer<ThreadState, Slot> RING_FILLER =
      (ts, slot) -> {
        slot.value = ts.counter;
        ts.counter++;
      };

  private static final BiConsumer<Blackhole, Slot> RING_CONSUMER =
      (bh, slot) -> bh.consume(slot.value);

  @Param({"1024"})
  public int capacity;

  /** Write-side benchmark structures. Drained by a background thread so they never fill. */
  MpscRingBuffer<Slot> ring;

  MpscArrayQueue<Slot> queue;

  /** E2e benchmark structures. JMH drives both sides via {@code @Group}; no background drainer. */
  MpscRingBuffer<Slot> e2eRing;

  MpscArrayQueue<Slot> e2eQueue;

  private volatile boolean stopDrainers;
  private Thread ringDrainer;
  private Thread queueDrainer;

  @Setup(Level.Trial)
  public void setup() {
    ring = new MpscRingBuffer<>(Slot::new, capacity);
    queue = new MpscArrayQueue<>(capacity);
    e2eRing = new MpscRingBuffer<>(Slot::new, capacity);
    e2eQueue = new MpscArrayQueue<>(capacity);

    stopDrainers = false;
    ringDrainer =
        new Thread(
            () -> {
              while (!stopDrainers) {
                if (ring.drain((Slot s) -> {}) == 0) Thread.yield();
              }
            },
            "RingVsQueueBenchmark-ringDrainer");
    ringDrainer.setDaemon(true);
    ringDrainer.start();

    queueDrainer =
        new Thread(
            () -> {
              while (!stopDrainers) {
                Slot s = queue.poll();
                if (s == null) Thread.yield();
              }
            },
            "RingVsQueueBenchmark-queueDrainer");
    queueDrainer.setDaemon(true);
    queueDrainer.start();
  }

  @TearDown(Level.Trial)
  public void teardown() throws InterruptedException {
    stopDrainers = true;
    ringDrainer.join(5_000);
    queueDrainer.join(5_000);
  }

  @State(Scope.Thread)
  public static class ThreadState {
    long counter;
  }

  // ============ Write-side throughput ============

  @Threads(8)
  @Benchmark
  public boolean write_ring_8p(final ThreadState ts) {
    return ring.tryWrite(ts, RING_FILLER);
  }

  /** Mirrors the SpanSnapshot pattern: allocate a fresh instance per publish, offer it. */
  @Threads(8)
  @Benchmark
  public boolean write_queue_8p(final ThreadState ts) {
    return queue.offer(new Slot(ts.counter++));
  }

  // ============ End-to-end producer/consumer ============

  @Group("e2e_ring_8p")
  @GroupThreads(8)
  @Benchmark
  public boolean e2e_ring_producer(final ThreadState ts) {
    return e2eRing.tryWrite(ts, RING_FILLER);
  }

  @Group("e2e_ring_8p")
  @GroupThreads(1)
  @Benchmark
  public int e2e_ring_consumer(final Blackhole bh) {
    int drained = e2eRing.drain(bh, RING_CONSUMER);
    if (drained == 0) Thread.yield();
    return drained;
  }

  @Group("e2e_queue_8p")
  @GroupThreads(8)
  @Benchmark
  public boolean e2e_queue_producer(final ThreadState ts) {
    return e2eQueue.offer(new Slot(ts.counter++));
  }

  @Group("e2e_queue_8p")
  @GroupThreads(1)
  @Benchmark
  public int e2e_queue_consumer(final Blackhole bh) {
    int drained = 0;
    Slot slot;
    while ((slot = e2eQueue.poll()) != null) {
      bh.consume(slot.value);
      drained++;
    }
    if (drained == 0) Thread.yield();
    return drained;
  }
}

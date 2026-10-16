package datadog.common.queue;

import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jctools.queues.MpscArrayQueue;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * {@link WorkQueue} against the things a caller would otherwise use, including the two it is
 * actually replacing. Published because the comparison exists whether or not we run it, and a
 * reader who has to measure it themselves is entitled to wonder what we found.
 *
 * <pre>
 * ./gradlew :utils:queue-utils:jmh -Pjmh.includes=AdmissionAlternatives -Pjmh.profilers=gc
 * </pre>
 *
 * <p><b>The decision this table is for.</b> Use {@link MpscArrayQueue} directly when one consumer,
 * the ring's own bound, and an element you already hold are the whole requirement -- it is the
 * floor here and nothing built on top of it will beat it. Reach for {@link WorkQueue} when the
 * element costs something to build, when a drop needs counting, when a failed consumer needs a
 * retry or a handler, or when the queue has a lifecycle. That is a real choice with a real answer
 * on both sides, and the numbers below are what it costs either way.
 *
 * <p><b>The baselines are not inventions.</b> {@code arrayBlocking} is {@code WafMetricCollector},
 * which offers into an {@code ArrayBlockingQueue(1024)}; {@code linkedBlocking} is {@code
 * RumInjectorMetrics}, which offers into a {@code LinkedBlockingQueue(1024)} and drops the return
 * value. Both build their element first and find out afterwards whether there was room. {@code
 * clqWithCounter} is the other thing people write: a {@link ConcurrentLinkedQueue} with an {@link
 * AtomicInteger} in front of it, guarded by a read and then incremented -- check-then-act, so two
 * threads at the boundary can both pass, and the bound is a suggestion. It is here because it is
 * common, not because it is correct; that it is racy is part of what is being compared.
 *
 * <p><b>Two halves, because the answer differs.</b> The {@code steady} arms admit and drain at one
 * thread: the per-operation cost with nothing else happening, which is where the alternatives look
 * their best. The {@code refused} arms sit on a full queue at four threads: the boundary, where a
 * bounded queue spends its time under load, and where building an element before asking is a wasted
 * allocation on every call. Read both. A caller whose queue is never full lives in the first table
 * and should weigh the API for what it buys, not for its speed.
 *
 * <p>Results. JDK 17, one machine, {@code -Pjmh.forks=1}; the four-thread arms carry wide error
 * bars and the ranking within the incumbents is not meaningful, but the separation from {@code
 * refusedWorkQueue} is an order of magnitude and survives any reading of them.
 *
 * <pre>
 * Benchmark                 threads    ns/op   B/op
 * steadyRawMpsc                   1     24.2     24
 * steadyWorkQueue                 1     36.3     24
 * steadyArrayBlocking             1     38.8     24
 * steadyClqWithCounter            1     42.8     48
 * steadyLinkedBlocking            1     46.2     48
 * refusedWorkQueue                4      7.2      0
 * refusedClqWithCounter           4    146.1     24
 * refusedLinkedBlocking           4    148.0     24
 * refusedRawMpsc                  4    160.2     24
 * refusedArrayBlocking            4    180.5     24
 * </pre>
 *
 * <p><b>What the two halves say.</b> Admitting, the raw ring is the floor at 24ns and nothing here
 * reaches it; {@link WorkQueue} costs 12ns more for the counted drop, the lifecycle, and the
 * producer callback. Both incumbents cost more than that, and the two linked queues allocate a node
 * per element on top of the element itself. So the API is not the expensive option even in the case
 * that flatters the alternatives.
 *
 * <p>Refusing, the separation is not subtle, and it is not really about the queue. {@code
 * refusedWorkQueue} does no allocation at all, because the place is claimed before the producer is
 * ever called and there was no place; every other arm has already built its element by the time it
 * asks. That is the designed difference rather than an artifact of the harness -- but read it as
 * such. A caller whose element is a preexisting object, or is free to build, keeps the shape of
 * this gap and not its size.
 *
 * <p><b>The honest caveat.</b> {@code refusedRawMpsc} is in the same band as the incumbents, which
 * is the reminder that the floor is a floor for admitting, not for refusing: a full ring still
 * touches a line the consumer is moving. {@code refusedWorkQueue} is fast because refusal is a load
 * against a counter no refusing thread writes -- see {@code ContendedAdmissionBenchmark}, which is
 * where that came from and what it cost before.
 */
@Fork(2)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class AdmissionAlternativesBenchmark {

  private static final int CAPACITY = 1024;

  private static final String ELEMENT = "element";

  /**
   * What the incumbents build before they ask. Stands in for a {@code WafMetric} or a metric
   * sample: not exact, but a real allocation with a stable footprint, so the arm that builds one
   * can be told from the arm that does not.
   */
  static final class Payload {
    final Object a;
    final long timestamp;

    Payload(Object a, long timestamp) {
      this.a = a;
      this.timestamp = timestamp;
    }
  }

  /** Only ever called once a place is already claimed, which is the whole difference. */
  private static final Producer<Payload> BUILDER = () -> new Payload(ELEMENT, System.nanoTime());

  private MpscArrayQueue<Payload> steadyRaw;
  private BlockingQueue<Payload> steadyArrayBlocking;
  private BlockingQueue<Payload> steadyLinkedBlocking;
  private Queue<Payload> steadyClq;
  private AtomicInteger steadyClqCount;
  private WorkQueue<Payload> steadyWork;

  private MpscArrayQueue<Payload> fullRaw;
  private BlockingQueue<Payload> fullArrayBlocking;
  private BlockingQueue<Payload> fullLinkedBlocking;
  private Queue<Payload> fullClq;
  private AtomicInteger fullClqCount;
  private WorkQueue<Payload> fullWork;

  @Setup
  public void setUp() {
    steadyRaw = new MpscArrayQueue<>(CAPACITY);
    steadyArrayBlocking = new ArrayBlockingQueue<>(CAPACITY);
    steadyLinkedBlocking = new LinkedBlockingQueue<>(CAPACITY);
    steadyClq = new ConcurrentLinkedQueue<>();
    steadyClqCount = new AtomicInteger();
    steadyWork = WorkQueues.createMpscQueue(CAPACITY);

    fullRaw = new MpscArrayQueue<>(16);
    fullArrayBlocking = new ArrayBlockingQueue<>(16);
    fullLinkedBlocking = new LinkedBlockingQueue<>(16);
    fullClq = new ConcurrentLinkedQueue<>();
    fullClqCount = new AtomicInteger();
    fullWork = WorkQueues.createMpscQueue(16);
    for (int i = 0; i < 16; i++) {
      Payload payload = new Payload(ELEMENT, i);
      fullRaw.offer(payload);
      fullArrayBlocking.offer(payload);
      fullLinkedBlocking.offer(payload);
      fullClq.offer(payload);
      fullClqCount.incrementAndGet();
      fullWork.tryPut(payload);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Steady: admit one, drain one, at a single thread. Every alternative at its best.
  // ---------------------------------------------------------------------------------------------

  @Benchmark
  @Threads(1)
  public void steadyRawMpsc(Blackhole bh) {
    bh.consume(steadyRaw.offer(new Payload(ELEMENT, System.nanoTime())));
    bh.consume(steadyRaw.poll());
  }

  /** {@code WafMetricCollector}: build, then offer, then find out. */
  @Benchmark
  @Threads(1)
  public void steadyArrayBlocking(Blackhole bh) {
    bh.consume(steadyArrayBlocking.offer(new Payload(ELEMENT, System.nanoTime())));
    bh.consume(steadyArrayBlocking.poll());
  }

  /** {@code RumInjectorMetrics}: the same, over a linked queue. */
  @Benchmark
  @Threads(1)
  public void steadyLinkedBlocking(Blackhole bh) {
    bh.consume(steadyLinkedBlocking.offer(new Payload(ELEMENT, System.nanoTime())));
    bh.consume(steadyLinkedBlocking.poll());
  }

  /** The hand-rolled bound: a read, then an increment, with a window between them. */
  @Benchmark
  @Threads(1)
  public void steadyClqWithCounter(Blackhole bh) {
    if (steadyClqCount.get() < CAPACITY) {
      steadyClqCount.incrementAndGet();
      bh.consume(steadyClq.offer(new Payload(ELEMENT, System.nanoTime())));
    }
    if (steadyClq.poll() != null) {
      steadyClqCount.decrementAndGet();
    }
  }

  @Benchmark
  @Threads(1)
  public void steadyWorkQueue(Blackhole bh) {
    bh.consume(steadyWork.tryPut(BUILDER));
    steadyWork.process(bh::consume);
  }

  // ---------------------------------------------------------------------------------------------
  // Refused: a full queue, four threads. The boundary, where a bounded queue lives under load.
  // ---------------------------------------------------------------------------------------------

  @Benchmark
  @Threads(4)
  public void refusedRawMpsc(Blackhole bh) {
    Payload payload = new Payload(ELEMENT, System.nanoTime());
    bh.consume(fullRaw.offer(payload));
    bh.consume(payload);
  }

  @Benchmark
  @Threads(4)
  public void refusedArrayBlocking(Blackhole bh) {
    Payload payload = new Payload(ELEMENT, System.nanoTime());
    bh.consume(fullArrayBlocking.offer(payload));
    bh.consume(payload);
  }

  @Benchmark
  @Threads(4)
  public void refusedLinkedBlocking(Blackhole bh) {
    Payload payload = new Payload(ELEMENT, System.nanoTime());
    bh.consume(fullLinkedBlocking.offer(payload));
    bh.consume(payload);
  }

  /**
   * The one arm where the hand-rolled guard is doing what it was written for: refusing without
   * touching the queue. It still builds the element first, because the caller had no way to know.
   */
  @Benchmark
  @Threads(4)
  public void refusedClqWithCounter(Blackhole bh) {
    Payload payload = new Payload(ELEMENT, System.nanoTime());
    if (fullClqCount.get() < 16) {
      fullClqCount.incrementAndGet();
      bh.consume(fullClq.offer(payload));
    }
    bh.consume(payload);
  }

  /** Never asked to build, because the place is claimed first and there was none. */
  @Benchmark
  @Threads(4)
  public void refusedWorkQueue(Blackhole bh) {
    bh.consume(fullWork.tryPut(BUILDER));
  }
}

package datadog.common.queue;

import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jctools.queues.MessagePassingQueue;
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
 * floor here. Reach for {@link WorkQueue} for its guarantees: an element is built, or a destructive
 * read made, only once capacity is claimed, through every admission form including batches and
 * reservations; refusal is reported rather than swallowed; the queue has a lifecycle; and consumer
 * failures can be retried or handled. The numbers below are what those guarantees cost. They are
 * not a claim that the API is faster.
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
 * <p>Results. JDK 17, one machine, {@code @Fork(2)}, {@code -Pjmh.profilers=gc}.
 *
 * <pre>
 * Benchmark                 threads    ns/op   B/op
 * steadyRawMpscFill               1     22.9     24
 * steadyRawMpsc                   1     23.8     24
 * steadyWorkQueue                 1     35.8     24
 * steadyArrayBlocking             1     38.0     24
 * steadyClqWithCounter            1     42.1     48
 * steadyLinkedBlocking            1     45.0     48
 * refusedWorkQueue                4      1.5      0
 * refusedRawMpscFill              4      2.6      0
 * refusedLinkedBlocking           4    139.9     24
 * refusedRawMpsc                  4    142.0     24
 * refusedClqWithCounter           4    149.6     24
 * refusedArrayBlocking            4    184.0     24
 * </pre>
 *
 * <p><b>Admitting.</b> The raw ring is the floor at about 23ns, with or without {@code fill}.
 * {@link WorkQueue} costs about 13ns more for the permit counter and the producer callback, and no
 * more allocation: that is the price of the guarantees above. The incumbents that build first cost
 * more than that anyway, and the two linked queues allocate a node per element on top of the
 * element itself.
 *
 * <p><b>Refusing.</b> Every arm that builds before it asks lands between 140 and 185ns with 24
 * B/op, and the gap to the claim-first arms is the element, not the queue: the same ring that takes
 * 142ns to refuse through {@code offer} refuses in 2.6ns through {@code fill}, which also claims a
 * slot before calling its supplier. {@code refusedWorkQueue} is cheaper still, because refusal is a
 * load against a counter no refusing thread writes -- see {@code ContendedAdmissionBenchmark}. A
 * caller whose element already exists, or is free to build, keeps the ordering of this gap and not
 * its size.
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

  /**
   * The same build for the ring's own {@code fill}, which also claims a slot before calling it. It
   * must not throw or return null: either would leave a claimed slot the consumer waits on forever.
   */
  private static final MessagePassingQueue.Supplier<Payload> RAW_BUILDER =
      () -> new Payload(ELEMENT, System.nanoTime());

  private MpscArrayQueue<Payload> steadyRaw;
  private MessagePassingQueue<Payload> steadyRawFill;
  private BlockingQueue<Payload> steadyArrayBlocking;
  private BlockingQueue<Payload> steadyLinkedBlocking;
  private Queue<Payload> steadyClq;
  private AtomicInteger steadyClqCount;
  private WorkQueue<Payload> steadyWork;

  private MpscArrayQueue<Payload> fullRaw;
  private MessagePassingQueue<Payload> fullRawFill;
  private BlockingQueue<Payload> fullArrayBlocking;
  private BlockingQueue<Payload> fullLinkedBlocking;
  private Queue<Payload> fullClq;
  private AtomicInteger fullClqCount;
  private WorkQueue<Payload> fullWork;

  @Setup
  public void setUp() {
    steadyRaw = new MpscArrayQueue<>(CAPACITY);
    steadyRawFill = Queues.mpscArrayQueue(CAPACITY);
    steadyArrayBlocking = new ArrayBlockingQueue<>(CAPACITY);
    steadyLinkedBlocking = new LinkedBlockingQueue<>(CAPACITY);
    steadyClq = new ConcurrentLinkedQueue<>();
    steadyClqCount = new AtomicInteger();
    steadyWork = WorkQueues.createMpscQueue(CAPACITY);

    fullRaw = new MpscArrayQueue<>(16);
    fullRawFill = Queues.mpscArrayQueue(16);
    fullArrayBlocking = new ArrayBlockingQueue<>(16);
    fullLinkedBlocking = new LinkedBlockingQueue<>(16);
    fullClq = new ConcurrentLinkedQueue<>();
    fullClqCount = new AtomicInteger();
    fullWork = WorkQueues.createMpscQueue(16);
    for (int i = 0; i < 16; i++) {
      Payload payload = new Payload(ELEMENT, i);
      fullRaw.offer(payload);
      fullRawFill.offer(payload);
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

  /**
   * The ring claiming before it builds, through the same factory {@link WorkQueue} uses. Against
   * {@code steadyWorkQueue}, this is what the permit counter and the API cost on top of it.
   */
  @Benchmark
  @Threads(1)
  public void steadyRawMpscFill(Blackhole bh) {
    bh.consume(steadyRawFill.fill(RAW_BUILDER, 1));
    bh.consume(steadyRawFill.poll());
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

  /** The ring's own claim-before-build: a full ring returns 0 without calling the supplier. */
  @Benchmark
  @Threads(4)
  public void refusedRawMpscFill(Blackhole bh) {
    bh.consume(fullRawFill.fill(RAW_BUILDER, 1));
  }

  /** Never asked to build, because the place is claimed first and there was none. */
  @Benchmark
  @Threads(4)
  public void refusedWorkQueue(Blackhole bh) {
    bh.consume(fullWork.tryPut(BUILDER));
  }
}

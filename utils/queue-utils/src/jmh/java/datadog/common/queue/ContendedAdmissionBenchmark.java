package datadog.common.queue;

import static java.util.concurrent.TimeUnit.SECONDS;

import java.util.concurrent.TimeUnit;
import org.jctools.queues.MpscArrayQueue;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
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
 * Admission with more than one thread arriving at once, which is the only way two of this module's
 * central costs become visible at all.
 *
 * <pre>
 * ./gradlew :utils:queue-utils:jmh -Pjmh.includes=ContendedAdmission -Pjmh.threads=8 -Pjmh.profilers=gc
 * </pre>
 *
 * <p><b>Allocation, as throughput.</b> A single thread allocates almost for free — a pointer bump
 * in a thread-local buffer — so a per-operation allocation shows up in {@code B/op} and barely at
 * all in {@code ns/op}, which is how an allocation on a hot path gets waved through. Several
 * threads allocating at once pay for it together: buffer refills, the memory bandwidth to touch
 * fresh cache lines, and eventually collection. That turns the allocation into a throughput number,
 * in the units a reviewer actually argues about. {@link AdmissionBenchmark} measured the
 * reservation path at 0 B/op against 12 for a shared refusal singleton, and it measured that at one
 * thread — where it is nearly free. This is where 12 B/op gets priced.
 *
 * <p>{@code refusedProducer} against {@code refusedBuildThenOffer} is this module's whole premise
 * stated as a benchmark. Both sit on a full queue and admit nothing. The first hands over a
 * producer and is never asked to build, because a place is claimed first and there was none; the
 * second builds its element and then discovers there is no room, which is the shape {@link
 * WorkQueue} exists to replace. At one thread the difference is an allocation that escape analysis
 * may well erase anyway. Under load it is the difference the API is claiming.
 *
 * <p><b>Contention, as itself.</b> {@link BaseWorkQueue#claimPlace} spends a place with one atomic
 * decrement and gives it back with a second when there was none to spend, so a refused admission
 * pays two read-modify-writes on one shared line — at the capacity boundary, where the most threads
 * are arriving at once. {@code refusedRaw} is the baseline that prices it: on the MPSC backing
 * jctools already enforces capacity through its own producer-index CAS, so a caller that never
 * reserves is paying the counter for a bound it was getting free, and the delta is what that costs.
 * The linked backing has no such baseline — {@code ConcurrentLinkedQueue} is unbounded and the
 * counter is the only thing bounding it, so there the comparison is against having no bound at all.
 *
 * <p>{@code steady} is the other half, and the commoner one: not full, with a consumer making room
 * as fast as producers take it. It is the only arm where the counter is incremented by a drain
 * while it is decremented by admission, contending on the same line from both directions. Its
 * consumer is a thread this class owns rather than a {@code @Group} member; the arm's own note says
 * why that distinction is load-bearing.
 *
 * <p>Neither cost is visible in {@link AdmissionBenchmark}, which is {@code @Threads(1)} and {@code
 * Scope.Thread} — every thread there gets its own queue, so there is nothing to contend on and
 * nothing to allocate alongside.
 *
 * <p>Results. Eight threads, one fork, {@code -Pjmh.profilers=gc}, JDK 25, on a machine with other
 * work on it -- so the absolute numbers run high and the intervals are wide. They are an
 * impression, not a baseline; {@code refusedRaw} on the same run is the control that says the load
 * is not what put a microsecond on the other rows.
 *
 * <pre>
 * Benchmark                 (backings)   ns/op            B/op
 * refusedProducer           MPSC         1035.8 +- 239    0
 * refusedProducer           LINKED       1162.5 +- 243    0
 * refusedQueue              MPSC          963.7 +- 292    0
 * refusedQueue              LINKED       1229.0 +-  57    0
 * refusedBuildThenOffer     MPSC          448.9 +-  60    32
 * refusedBuildThenOffer     LINKED        460.0 +-  16    32
 * refusedRaw                MPSC            3.4 +-   1    0
 * refusedRaw                LINKED          3.4 +-   1    0
 * steady                    MPSC          798.7 +- 266    0
 * steady                    LINKED       1008.4 +- 724    8.75
 * </pre>
 *
 * <p><b>What this says, including the part that does not flatter the API.</b> The permit counter is
 * the dominant cost at the boundary, by two and a half orders of magnitude: a refused admission is
 * ~960ns against ~3.4ns for the same rejection taken on jctools' own producer-index CAS. Eight
 * threads doing two read-modify-writes on one shared line is the whole of that gap. On the MPSC
 * backing that is being paid for a bound the ring was already enforcing for free.
 *
 * <p>And so the premise pair does not come out the way the module's argument wants. {@code
 * refusedProducer} does hold 0 B/op where {@code refusedBuildThenOffer} pays 32 -- reserve-before-
 * build does what it claims -- but it is slower in {@code ns/op}, ~1036 against ~449. Read the pair
 * carefully before concluding anything from it: {@code refusedBuildThenOffer} offers to the raw
 * queue, so it prices an allocation without a counter, while {@code refusedProducer} prices a
 * counter without an allocation. It is not one variable. What the two together do establish is the
 * ordering: under contention at the boundary, the counter costs more than the allocation it avoids.
 * The allocation win is real and the contention cost is larger, and a call site that is refusing
 * often is paying for reserve-before-build rather than being paid by it.
 *
 * <p>None of which is an argument against the API at a call site that mostly succeeds -- {@code
 * steady} is the arm for that, and it allocates nothing on the MPSC backing against 8.75 B/op of
 * linked node on the other. It is an argument for measuring the boundary before putting this in
 * front of a producer that lives there.
 */
@Fork(2)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Threads(4)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class ContendedAdmissionBenchmark {

  public enum Backings {
    MPSC,
    LINKED
  }

  /** Big enough that the steady arm is not living at the boundary by accident. */
  private static final int CAPACITY = 1024;

  private static final String ELEMENT = "element";

  /**
   * Stands in for the element a real producer builds — a client-stats {@code SpanSnapshot} and its
   * tag arrays. Its size is not meant to be exact; what matters is that it is a real allocation
   * with a stable footprint, so the arm that builds one can be compared against the arm that does
   * not.
   */
  static final class Payload {
    final Object a;
    final Object b;
    final long duration;
    final int hash;

    Payload(Object a, Object b, long duration) {
      this.a = a;
      this.b = b;
      this.duration = duration;
      this.hash = a.hashCode() ^ b.hashCode();
    }
  }

  /** Allocates on every call, and is only ever called once a place is already claimed. */
  private static final Producer<Payload> BUILDER =
      () -> new Payload(ELEMENT, ELEMENT, System.nanoTime());

  @Param({"MPSC", "LINKED"})
  public Backings backings;

  /** Never full, drained concurrently by {@link #consume}. */
  private WorkQueue<String> queue;

  /** Filled once in setup and never drained, so every admission is refused. */
  private WorkQueue<String> full;

  /** The same, typed for the producer arm. */
  private WorkQueue<Payload> fullPayloads;

  /**
   * A full queue with no permit counter, for the two baselines. Unaffected by {@code backings} — it
   * is the same number in both rows, and is what the MPSC backing would cost on the ring's own
   * bound alone.
   */
  private MpscArrayQueue<Object> raw;

  /** The one consumer for {@link #steady}. Owned here, not by JMH -- see that arm's note. */
  private Thread drain;

  private volatile boolean draining;

  @Setup
  public void setUp() {
    queue = create(CAPACITY);
    full = create(16);
    while (full.tryPut(ELEMENT)) {
      // fill it, so claimPlace always has to back out
    }
    fullPayloads =
        backings == Backings.MPSC ? WorkQueues.createMpscQueue(16) : WorkQueues.createMpmcQueue(16);
    while (fullPayloads.tryPut(new Payload(ELEMENT, ELEMENT, 0L))) {
      // same, for the producer arm
    }
    raw = new MpscArrayQueue<>(16);
    while (raw.offer(ELEMENT)) {
      // same, through jctools' own rejection
    }
    draining = true;
    drain =
        new Thread(
            () -> {
              while (draining) {
                // Not timed, and deliberately not throttled: the point is to keep the steady arm
                // off the capacity boundary and to keep the counter's increment side busy.
                queue.process(CAPACITY, e -> {});
              }
            },
            "contended-admission-drain");
    drain.setDaemon(true);
    drain.start();
  }

  @TearDown
  public void tearDown() throws InterruptedException {
    draining = false;
    drain.join(SECONDS.toMillis(5));
  }

  private WorkQueue<String> create(int capacity) {
    return backings == Backings.MPSC
        ? WorkQueues.createMpscQueue(capacity)
        : WorkQueues.createMpmcQueue(capacity);
  }

  /** Reserve-before-build: the producer is never asked, so nothing is allocated. */
  @Benchmark
  public void refusedProducer(Blackhole bh) {
    bh.consume(fullPayloads.tryPut(BUILDER));
  }

  /**
   * Build-then-offer: what the same rejection costs when the element is constructed before the
   * queue gets a say. Consumed through the blackhole so it genuinely escapes, the way an element
   * handed to a queue in another class does — otherwise escape analysis erases the allocation this
   * arm exists to charge for.
   */
  @Benchmark
  public void refusedBuildThenOffer(Blackhole bh) {
    Payload payload = new Payload(ELEMENT, ELEMENT, System.nanoTime());
    bh.consume(raw.offer(payload));
    bh.consume(payload);
  }

  /** Two RMWs on the shared counter, every call, from every thread. */
  @Benchmark
  public void refusedQueue(Blackhole bh) {
    bh.consume(full.tryPut(ELEMENT));
  }

  /** The bound jctools gives for free, for the delta. */
  @Benchmark
  public void refusedRaw(Blackhole bh) {
    bh.consume(raw.offer(ELEMENT));
  }

  /**
   * The commoner half: not full, with a consumer making room about as fast as producers take it, so
   * the counter is incremented by a drain while it is decremented by admission -- contending on the
   * same line from both directions.
   *
   * <p>Every JMH thread is a producer here, and the consumer is the dedicated {@link #drain} thread
   * started in setup rather than a {@code @Group} member. That is not a stylistic choice. A group's
   * {@code @GroupThreads(1)} fixes the consumer count *per group*, and JMH instantiates as many
   * groups as the thread count allows -- so {@code -Pjmh.threads=8} against a group of 4 yields two
   * consumers. Two concurrent {@code poll()}s on the single-consumer MPSC ring do not fail; they
   * spin inside jctools' gap-wait and the iteration never ends. Owning the consumer outright makes
   * the arm correct at any thread count, which is what the project's spot-check flags hand it.
   */
  @Benchmark
  public void steady(Blackhole bh) {
    bh.consume(queue.tryPut(ELEMENT));
  }
}

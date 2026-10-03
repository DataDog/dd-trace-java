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
 * That delta is what the relaxed read in {@code claimPlace} brought down from ~960ns to ~5ns. The
 * linked backing has no such baseline — {@code ConcurrentLinkedQueue} is unbounded and the counter
 * is the only thing bounding it, so there the comparison is against having no bound at all.
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
 * impression, not a baseline; {@code refusedRaw} is the control on every run.
 *
 * <p>The {@code before} column is the decrement-then-back-out admission this benchmark was written
 * to price. The {@code after} column is the same run once {@link BaseWorkQueue#claimPlace} took to
 * reading the count before spending from it.
 *
 * <pre>
 * Benchmark                 (backings)   before ns/op   after ns/op   B/op
 * refusedProducer           MPSC             1035.8           7.3     0
 * refusedProducer           MPMC             1162.5           7.2     0
 * refusedQueue              MPSC              963.7           7.2     0
 * refusedQueue              MPMC             1229.0           7.3     0
 * refusedBuildThenOffer     MPSC              448.9         212.7     32
 * refusedBuildThenOffer     MPMC              460.0         228.7     32
 * refusedRaw                MPSC                3.4           2.5     0
 * refusedRaw                MPMC                3.4           2.6     0
 * steady                    MPSC              798.7         489.7     0
 * steady                    MPMC             1008.4         252.8     0
 * </pre>
 *
 * <p>The {@code before} column predates two changes, not one: the MPMC rows were taken when that
 * backing was a {@link java.util.concurrent.ConcurrentLinkedQueue} rather than an array ring. They
 * are kept because the column exists to show the 120x, which is a property of the counter and not
 * of the structure underneath it -- the refusal never reaches the backing at all. The {@code after}
 * column is a single fresh run of everything, so the arms are comparable with each other.
 *
 * <p><b>What this measured.</b> Two read-modify-writes on one shared line, taken by eight threads
 * at the capacity boundary, cost about 120x what the same rejection costs when the first of them is
 * a load instead. A refusal is now ~7.3ns against ~2.5ns for jctools' own producer-index CAS, so
 * the permit counter costs on the order of 5ns over a bound the ring was already enforcing --
 * against ~960ns before, where it dwarfed everything else the API does. {@code steady} moved with
 * it, and for the same reason: eight producers against one drain thread keep the queue saturated,
 * so most of that arm is refusals too.
 *
 * <p>Treat the ratio with more suspicion than the direction. Two contended read-modify-writes
 * should not cost 960ns on a quiet machine -- tens of nanoseconds is the expected order -- so some
 * of that baseline is this machine's other work amplifying the contention, threads losing their
 * slice mid-sequence with the line hot. The ~7.9ns is tight and the mechanism is not in doubt; a
 * quiet run will likely show a smaller multiple against a smaller before.
 *
 * <p>{@code steady} is the one arm where the two backings now separate, and by more than their
 * intervals: 252.8ns against 489.7ns, the MPMC ring ahead of the MPSC one. Eight producers against
 * a single drain thread is a shape the multi-consumer ring is built for and the single-consumer
 * ring is not, and the gap is not evidence about admission -- read it as the drain, not the claim.
 *
 * <p>Attribution, since two changes landed together: this is the read, not the folding of the
 * closed flag into the count. Removing a volatile boolean load cannot account for 950ns. Folding it
 * was structural -- one word of state instead of two that have to agree.
 *
 * <p><b>The premise pair, which now goes the way the module argues.</b> {@code refusedProducer}
 * against {@code refusedBuildThenOffer} is reserve-before-build against building first and finding
 * out after: ~8ns and 0 B/op against ~422ns and 32 B/op. Before the read it was the awkward result
 * -- 0 B/op but slower in {@code ns/op} -- because the counter cost more than the allocation it
 * avoided. It no longer does. Read the pair for what it is even so: the build-then-offer arm has no
 * counter and the producer arm has no allocation, so it is not one variable. What it establishes is
 * the ordering, and the ordering has reversed.
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
    MPMC
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

  @Param({"MPSC", "MPMC"})
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

package datadog.common.queue;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * The three admission forms measured against each other on a real queue, to answer one question:
 * does the reservation object survive escape analysis? If it does, the reserve route costs what the
 * producer routes cost and {@link BiContextualProducer} was never needed for client-side stats. If
 * it does not, the producer callbacks are earning their contortions.
 *
 * <pre>./gradlew :utils:queue-utils:jmh -Pjmh.includes=Admission -Pjmh.profilers=gc</pre>
 *
 * <p>{@code gc.alloc.rate.norm} is the number that answers it; the timings are secondary and are
 * muddied on purpose, because every arm consumes the item it just admitted to keep the queue at
 * steady state. The element itself is preallocated in every arm, including the producer ones, so
 * what is being compared is the admission machinery and not the cost of building an element.
 *
 * <p>The {@code backings} parameter is the template-method question. {@code store} and {@code
 * retrieve} are one call site each, shared by every backing in the process, so their receiver
 * profile is global: {@code ONE} loads a single concrete subclass and C2 inlines through them,
 * {@code BOTH} loads two and it still does behind a type guard, {@code THREE} is the cliff. Every
 * arm drives two extra queues through the same sites for the same number of iterations, so the
 * traffic is identical and only the number of distinct types in it changes.
 *
 * <p>Results:
 *
 * <pre>
 * Benchmark             ONE     BOTH    THREE    THREE-ONE
 * tryPutElement        21.19   20.88   22.44      +1.3
 * tryPutProducer       20.82   20.89   21.81      +1.0
 * tryPutContextual     20.83   20.86   22.85      +2.0
 * tryPutBiContextual   21.25   21.29   21.83      +0.6
 * reserveAndFill       21.04   22.92   27.61      +6.6
 * reserveMixed         12.69   12.75   13.71      +1.0
 * reserveRefused        8.45    7.25    7.16       0
 * </pre>
 *
 * <p>JDK 25, {@code -Pjmh.forks=2}, ns/op. Every arm allocates 0.01 B/op or less, {@code THREE}
 * included. Error bars are within ±0.6 except {@code reserveAndFill} at {@code BOTH} (±4.3) and
 * {@code THREE} (±2.3), and {@code reserveRefused} at {@code ONE} (±1.9).
 *
 * <p>Two things to read off it. A second backing is free — every arm is flat from {@code ONE} to
 * {@code BOTH}. A third is not free but is small: one to two nanoseconds on a twenty-one nanosecond
 * operation, because an admit-and-drain pays two uncontended atomics and a ring compare-and-set,
 * and an out-of-line call is little against memory ordering. {@code reserveRefused} is the control:
 * it never reaches {@code store}, and it does not move.
 *
 * <p>{@code reserveAndFill} is the exception worth knowing about, at roughly 30%. Its {@code store}
 * happens inside {@link Reservation#fill} on an object that only exists if escape analysis deletes
 * it, so that arm is not paying for a virtual call so much as for a longer chain of optimizations
 * having to survive one. The allocation still goes away — the reservation is still scalar-replaced
 * at {@code THREE} — but the call it wraps no longer folds into the caller. A caller admitting
 * through a reservation is the one with something to lose from a third backing.
 *
 * <p>The refusal-design figure that {@link BaseWorkQueue} cites lives here too, and is unchanged by
 * any of the above: {@code reserveMixed} measured 12 B/op when a refused reservation was a shared
 * singleton and 0 B/op when it is its own allocation, on JDK 17. Only that arm can tell the two
 * designs apart — {@code reserveAndFill} and {@code reserveRefused} each see a single outcome, so
 * C2 prunes the branch that never runs and there is no merge left to defeat escape analysis.
 *
 * <p>{@link ThirdBackingWorkQueue} is the third type, and lives in this source set rather than in
 * the module: the question is what a third backing <i>would</i> cost, and shipping one to find out
 * would answer a different question.
 */
@Fork(2)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Threads(1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class AdmissionBenchmark {

  public enum Backings {
    ONE,
    BOTH,
    THREE
  }

  private static final String ELEMENT = "element";

  private static final Producer<String> PRODUCER = () -> ELEMENT;

  private static final ContextualProducer<String, String> CONTEXTUAL = context -> context;

  private static final BiContextualProducer<String, String, String> BI_CONTEXTUAL =
      (first, second) -> first;

  @Param({"ONE", "BOTH", "THREE"})
  public Backings backings;

  /** Alternates the reserving queue in {@link #reserveMixed}, so one site sees both outcomes. */
  private int mixer;

  /** The queue under test. */
  private WorkQueue<String> queue;

  /** Kept full for the whole run, so its reservations are always refused. */
  private WorkQueue<String> full;

  /**
   * Two more queues driven through the same call sites as the queue under test, and the whole of
   * what the parameter varies. Every arm allocates both and drives both, so the traffic arriving at
   * {@code store} and {@code retrieve} is identical; only the number of distinct types in it
   * changes. An arm that skipped the loop would also differ in how hard its call sites had been
   * exercised before measurement, which is not the question being asked.
   */
  private WorkQueue<String> second;

  private WorkQueue<String> third;

  @Setup
  public void setUp() {
    queue = WorkQueues.createMpscQueue(1024);
    full = WorkQueues.createMpscQueue(1);
    full.tryPut(ELEMENT);
    second =
        backings == Backings.ONE
            ? WorkQueues.createMpscQueue(1024)
            : WorkQueues.createMpmcQueue(1024);
    third =
        backings == Backings.THREE
            ? new ThirdBackingWorkQueue<>(1024)
            : WorkQueues.createMpscQueue(1024);
  }

  /**
   * Runs the other backings through the same methods, so every loaded type reaches the shared call
   * sites. Repeated before every iteration rather than once per trial: the measured loop drives one
   * type only, and a profile that saw the others just once at startup is not the profile a process
   * with several live backings actually has.
   */
  @Setup(Level.Iteration)
  public void pollute(Blackhole bh) {
    for (int i = 0; i < 20_000; i++) {
      second.tryPut(ELEMENT);
      second.process(bh::consume);
      third.tryPut(ELEMENT);
      third.process(bh::consume);
    }
  }

  @Benchmark
  public void tryPutElement(Blackhole bh) {
    bh.consume(queue.tryPut(ELEMENT));
    queue.process(bh::consume);
  }

  @Benchmark
  public void tryPutProducer(Blackhole bh) {
    bh.consume(queue.tryPut(PRODUCER));
    queue.process(bh::consume);
  }

  @Benchmark
  public void tryPutContextual(Blackhole bh) {
    bh.consume(queue.tryPut(ELEMENT, CONTEXTUAL));
    queue.process(bh::consume);
  }

  @Benchmark
  public void tryPutBiContextual(Blackhole bh) {
    bh.consume(queue.tryPut(ELEMENT, ELEMENT, BI_CONTEXTUAL));
    queue.process(bh::consume);
  }

  @Benchmark
  public void reserveAndFill(Blackhole bh) {
    Reservation<String> place = queue.tryReserve();
    try {
      if (place.granted()) {
        place.fill(ELEMENT);
      }
    } finally {
      place.close();
    }
    queue.process(bh::consume);
  }

  /** The refusal path, which is where the shared singleton was supposed to be paying off. */
  @Benchmark
  public void reserveRefused(Blackhole bh) {
    Reservation<String> place = full.tryReserve();
    try {
      bh.consume(place.granted());
    } finally {
      place.close();
    }
  }

  /**
   * Both outcomes through one call site, which is the only shape where how a refusal is represented
   * can cost anything.
   *
   * <p>The two arms above each see a single outcome, so C2 prunes the branch that never runs and
   * there is no merge to defeat escape analysis — they read zero whether a refusal is a shared
   * singleton or its own allocation, and neither one can tell the two designs apart. A caller whose
   * queue is nearly always accepting is genuinely in that case. A caller that sits at the boundary,
   * refusing about as often as it admits, is in this one.
   */
  @Benchmark
  public void reserveMixed(Blackhole bh) {
    WorkQueue<String> target = (mixer++ & 1) == 0 ? queue : full;
    Reservation<String> place = target.tryReserve();
    try {
      if (place.granted()) {
        place.fill(ELEMENT);
      }
    } finally {
      place.close();
    }
    queue.process(bh::consume);
  }
}

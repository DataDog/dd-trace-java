package datadog.common.queue;

import java.util.concurrent.TimeUnit;
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
 * <p>The {@code backings} parameter is the template-method question: {@code ONE} loads a single
 * concrete subclass, so {@code store} is monomorphic and C2 inlines it outright; {@code BOTH} loads
 * two, which C2 still inlines behind a type guard. A third backing would be the cliff. Measuring
 * both is how we find out whether the inheritance layout costs anything today, or only threatens
 * to.
 *
 * <p>Results, filled in as they are measured:
 *
 * <pre>
 * Benchmark                (backings)    ns/op    B/op
 * tryPutElement            ONE           ?        ?
 * tryPutElement            BOTH          ?        ?
 * tryPutContextual         ONE           ?        ?
 * tryPutContextual         BOTH          ?        ?
 * tryPutBiContextual       ONE           ?        ?
 * tryPutBiContextual       BOTH          ?        ?
 * reserveAndFill           ONE           ?        ?
 * reserveAndFill           BOTH          ?        ?
 * reserveRefused           ONE           ?        ?
 * reserveRefused           BOTH          ?        ?
 * </pre>
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
    BOTH
  }

  private static final String ELEMENT = "element";

  private static final Producer<String> PRODUCER = () -> ELEMENT;

  private static final ContextualProducer<String, String> CONTEXTUAL = context -> context;

  private static final BiContextualProducer<String, String, String> BI_CONTEXTUAL =
      (first, second) -> first;

  @Param({"ONE", "BOTH"})
  public Backings backings;

  /** The queue under test. */
  private WorkQueue<String> queue;

  /** Kept full for the whole run, so its reservations are always refused. */
  private WorkQueue<String> full;

  /**
   * Present only to put a second concrete subclass into the profile. Its call sites are the same
   * ones the queue under test uses, which is exactly the pollution being measured.
   */
  private WorkQueue<String> other;

  @Setup
  public void setUp(Blackhole bh) {
    queue = WorkQueues.createMpscQueue(1024);
    full = WorkQueues.createMpscQueue(1);
    full.tryPut(ELEMENT);
    if (backings == Backings.BOTH) {
      other = WorkQueues.createMpmcQueue(1024);
      // Warm the other backing through the same methods, so both types reach the call sites.
      for (int i = 0; i < 20_000; i++) {
        other.tryPut(ELEMENT);
        other.process(bh::consume);
      }
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
}

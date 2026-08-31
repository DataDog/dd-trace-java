package datadog.common.queue;

import java.util.ArrayList;
import java.util.List;
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
 * What one claim for a run of elements saves over one claim per element, with nothing else going
 * on. The contended half of the question -- what the same batch costs the producers next to it --
 * is {@link ContendedBatchAdmissionBenchmark}; this one is the ceiling on the saving.
 *
 * <pre>
 * ./gradlew :utils:queue-utils:jmh -Pjmh.includes=BatchAdmission -Pjmh.profilers=gc
 * </pre>
 *
 * <p>Each arm admits {@code batchSize} elements and drains the same number, so the queue sits well
 * off its bound and every admission succeeds. Timings are per call, which is per batch: divide by
 * {@code batchSize} for the per-element figure the pair is actually comparing. The elements are
 * built once in setup, so what is measured is admission and not construction.
 *
 * <p><b>What the saving can be.</b> Admission costs a claim on the shared counter and a store into
 * the backing. Batching removes all but one of the claims and none of the stores, and the MPSC
 * ring's store is itself a compare-and-set on the producer index. So the ceiling here is a little
 * under half the per-element cost on that backing, and rather more on the linked one, where the
 * store is a node allocation and a CAS on the tail but the counter is the only bound there is.
 *
 * <p><b>Why the batch arm cannot simply win.</b> It walks an iterator and refills a claim every
 * {@code MAX_BATCH_CLAIM} elements, which the loop arm does not. The sizes run from 4 to 128 to
 * find where that bookkeeping stops costing more than the claims it removes -- and the answer,
 * below, is that at one thread it never does.
 *
 * <p><b>Results, and they go the other way.</b> One fork, five iterations, JDK 25, on a machine
 * with other work on it -- but the intervals here are tight (a percent or two), because there is no
 * contention to amplify anything. The per-element columns are the call divided by {@code
 * batchSize}.
 *
 * <pre>
 * Benchmark          (backings)  (batchSize)     ns/op   ns/element
 * batchOfElements    MPSC        4                68.4         17.1
 * loopOfElements     MPSC        4                58.1         14.5
 * batchOfElements    MPSC        8               137.0         17.1
 * loopOfElements     MPSC        8               114.9         14.4
 * batchOfElements    MPSC        32              519.6         16.2
 * loopOfElements     MPSC        32              456.7         14.3
 * batchOfElements    MPSC        128            2084.0         16.3
 * loopOfElements     MPSC        128            1825.2         14.3
 * batchOfElements    LINKED      4                83.2         20.8
 * loopOfElements     LINKED      4                83.7         20.9
 * batchOfElements    LINKED      32              660.3         20.6
 * loopOfElements     LINKED      32              648.5         20.3
 * batchOfElements    LINKED      128            2613.3         20.4
 * loopOfElements     LINKED      128            2510.0         19.6
 * </pre>
 *
 * <p>The producer arms track the element arms to within a nanosecond per element at every size, on
 * both backings, so they are left out of the table above; the claim is what they share and the
 * producer call is inlined away.
 *
 * <p><b>Batching is slower here, by about 2ns per element on MPSC and a shade on LINKED.</b> That
 * is the honest ceiling on the saving, and it is negative: an uncontended atomic add is a few
 * cycles, and removing 31 of them out of 32 does not pay for walking an iterator and refilling a
 * claim. Some of that gap is the iterator itself rather than the claim bookkeeping -- the loop arm
 * indexes an {@code ArrayList} and the batch arm cannot, because its argument is a {@code
 * Collection}. It does not much matter which half it is: the size is the same either way and the
 * sign does not change.
 *
 * <p>Nor does the sign change with batch size, which is the useful part. The saving does not turn
 * positive at 128, so there is no crossover to find and no minimum batch size to recommend on this
 * evidence. Whatever batching is worth, it is not worth anything to a producer that is alone.
 *
 * <p><b>Which is the point.</b> The claim's cost is not in the instruction, it is in the line
 * everyone else is reading -- and a benchmark with one thread has taken that out. Read this table
 * as the floor and {@link ContendedBatchAdmissionBenchmark} as the case: there the same batching
 * producer is between 1.3x and 3.3x faster than the same loop. A caller choosing between them
 * should be asking how many threads share the queue, not how long its batches are.
 */
@Fork(2)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Threads(1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class BatchAdmissionBenchmark {

  public enum Backings {
    MPSC,
    LINKED
  }

  /** Comfortably above the largest batch, so no arm is admitting at the bound. */
  private static final int CAPACITY = 1024;

  private static final String ELEMENT = "element";

  /**
   * Turns a source element into the queue's element without allocating, as a real one would not.
   */
  private static final BiContextualProducer<String, String, String> PRODUCER =
      (source, context) -> source;

  @Param({"MPSC", "LINKED"})
  public Backings backings;

  @Param({"4", "8", "32", "128"})
  public int batchSize;

  private WorkQueue<String> queue;

  /** Built once: the arms compare admission, not the cost of producing a list. */
  private List<String> elements;

  @Setup
  public void setUp() {
    queue =
        backings == Backings.MPSC
            ? WorkQueues.createMpscQueue(CAPACITY)
            : WorkQueues.createMpmcQueue(CAPACITY);
    elements = new ArrayList<>(batchSize);
    for (int i = 0; i < batchSize; i++) {
      elements.add(ELEMENT);
    }
  }

  /** One claim per run of elements. */
  @Benchmark
  public void batchOfElements(Blackhole bh) {
    bh.consume(queue.tryPutBatch(elements));
    bh.consume(queue.process(batchSize, bh::consume));
  }

  /** The same admissions, one claim each: what a caller writes without the batch API. */
  @Benchmark
  public void loopOfElements(Blackhole bh) {
    for (int i = 0; i < batchSize; i++) {
      bh.consume(queue.tryPut(elements.get(i)));
    }
    bh.consume(queue.process(batchSize, bh::consume));
  }

  /** The producer form, where the claim also gates whether an element is built at all. */
  @Benchmark
  public void batchOfProducers(Blackhole bh) {
    bh.consume(queue.tryPutBatch(elements, ELEMENT, PRODUCER));
    bh.consume(queue.process(batchSize, bh::consume));
  }

  @Benchmark
  public void loopOfProducers(Blackhole bh) {
    for (int i = 0; i < batchSize; i++) {
      bh.consume(queue.tryPut(elements.get(i), ELEMENT, PRODUCER));
    }
    bh.consume(queue.process(batchSize, bh::consume));
  }
}

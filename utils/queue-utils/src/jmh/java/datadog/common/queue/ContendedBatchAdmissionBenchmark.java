package datadog.common.queue;

import static java.util.concurrent.TimeUnit.SECONDS;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.AuxCounters;
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
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * What a batching producer costs the producers admitting next to it, which is the half of batch
 * claiming that its own throughput number cannot show. {@link BatchAdmissionBenchmark} measures
 * what the batcher saves; this measures who pays for it.
 *
 * <pre>
 * ./gradlew :utils:queue-utils:jmh -Pjmh.includes=ContendedBatchAdmission
 * </pre>
 *
 * <p><b>The mechanism being priced.</b> A claim spends places by driving the shared count down and
 * gives back what it could not use, and every other producer's admission opens by comparing that
 * same count against zero. So while a claim of {@code n} is outstanding the count reads {@code n}
 * lower than it is, and a neighbour arriving at a queue with fewer than {@code n} places left is
 * turned away by a load -- correctly, in the sense that the places really were spoken for, and
 * spuriously, in the sense that most of them are about to come back. That window is why {@link
 * BaseWorkQueue} caps a claim rather than taking the whole batch at once.
 *
 * <p><b>The shape.</b> Two groups of four threads, differing only in what the first thread does. In
 * {@code batched} it admits {@code batchSize} elements with one {@code tryPutBatch}; in {@code
 * unbatched} it admits the same elements with a loop of {@code tryPut}. The other three threads are
 * identical in both: a single {@code tryPut} each, and they are the measurement. The comparison
 * that answers the question is {@code neighbourOfBatch} against {@code neighbourOfLoop} -- same
 * work, same thread count, same queue, and the only difference is how the fourth thread claims.
 *
 * <p>Read the neighbour arms as a pair and the producer arms as a pair; across the two pairs the
 * units differ, because a batch call admits {@code batchSize} elements and a single call admits
 * one.
 *
 * <p><b>What would count as too large a cap.</b> If {@code neighbourOfBatch} is materially slower
 * than {@code neighbourOfLoop}, or refuses materially more often, the cap is buying the batcher's
 * throughput out of its neighbours' -- which is a trade a shared queue should not make quietly.
 * {@code refused} is reported alongside for that reason: a neighbour turned away costs almost
 * nothing in time and everything in outcome, so the timing alone would hide it.
 *
 * <p>The consumer is a thread this class owns rather than a group member, for the reason {@link
 * ContendedAdmissionBenchmark#steady} sets out: a group's consumer count scales with the thread
 * count, and two concurrent polls on the single-consumer MPSC ring hang inside jctools' gap-wait.
 *
 * <p><b>Results.</b> Four threads, one fork, five iterations, JDK 25, on a machine with other work
 * on it. The intervals are wide -- often half the mean -- so read the producer column for its
 * direction and the neighbour columns with real suspicion. {@code refused%} is {@code refused} over
 * {@code attempts}, both summed over the iteration.
 *
 * <pre>
 * (backings) (batchSize)   producer ns/op   neighbour ns/op   neighbour refused%
 * MPSC        8    batched         1038.3             232.7                 67.6
 * MPSC        8    looping         2331.8             294.9                 49.2
 * MPSC        32   batched         1637.3             213.2                 67.6
 * MPSC        32   looping         7392.9             221.2                 68.0
 * MPMC        8    batched          705.1             170.7                 81.4
 * MPMC        8    looping         1479.7             185.6                 79.7
 * MPMC        32   batched         1389.4             186.9                 78.5
 * MPMC        32   looping         3894.4             121.0                 89.1
 * </pre>
 *
 * <p><b>The producer column is the finding.</b> Batching wins everywhere it is contended, by 2.1x
 * at eight elements on MPMC and by 4.5x at thirty-two on MPSC, and the advantage grows with the
 * batch -- the opposite of {@link BatchAdmissionBenchmark}, where the same code at one thread is
 * about 2ns per element slower. That is the whole case for batch claiming stated in two tables: it
 * buys nothing from the instruction it removes and a great deal from the cache line it stops
 * touching.
 *
 * <p><b>The neighbour columns do not resolve.</b> The timings move in opposite directions on the
 * two backings and every gap sits inside its own interval. The refusal rates do have a shape --
 * every neighbour refuses more often the faster its neighbour admits -- but that is not the claim
 * window, or not only. One consumer bounds total admissions, so a producer that admits three times
 * faster takes three times the share, and its neighbours find the queue full more often. That is
 * the batcher succeeding, not the cap leaking.
 *
 * <p>Which means this benchmark does not yet separate the two effects it was built to tell apart: a
 * neighbour refused because places are transiently claimed, and a neighbour refused because
 * somebody else's work got in first. Distinguishing them wants a quiet machine and a consumer fast
 * enough that the queue is not the bottleneck. Until then {@code MAX_BATCH_CLAIM} rests on the
 * argument rather than the measurement -- the dip is real and bounded by the cap whether or not
 * this run can see it.
 */
@Fork(2)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class ContendedBatchAdmissionBenchmark {

  public enum Backings {
    MPSC,
    MPMC
  }

  /**
   * A neighbour turned away costs almost nothing in time and everything in outcome, so the timing
   * alone would hide the effect this class exists to find -- a batch that makes its neighbours
   * refuse looks, in {@code ns/op}, like a batch that costs them nothing.
   */
  @State(Scope.Thread)
  @AuxCounters(AuxCounters.Type.EVENTS)
  public static class Outcomes {
    public long refused;

    /**
     * Reported alongside {@link #refused} because the counter is a sum, not a rate, and the two
     * arms do not run the same number of operations -- a neighbour that got faster refused more
     * times for that reason alone. The ratio is the comparable number.
     */
    public long attempts;

    @Setup(Level.Iteration)
    public void reset() {
      refused = 0;
      attempts = 0;
    }
  }

  /**
   * Deliberately not much larger than a batch. The whole effect lives at the boundary: a queue with
   * room to spare absorbs an outstanding claim without any neighbour noticing.
   */
  private static final int CAPACITY = 256;

  private static final String ELEMENT = "element";

  @Param({"MPSC", "MPMC"})
  public Backings backings;

  @Param({"8", "32"})
  public int batchSize;

  private WorkQueue<String> queue;

  private List<String> elements;

  /** The one consumer. Owned here, not by JMH -- see the class note. */
  private Thread drain;

  private volatile boolean draining;

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
    draining = true;
    drain =
        new Thread(
            () -> {
              while (draining) {
                // Unthrottled: the arms are about who gets in, not about starving the queue.
                queue.process(CAPACITY, e -> {});
              }
            },
            "contended-batch-admission-drain");
    drain.setDaemon(true);
    drain.start();
  }

  @TearDown
  public void tearDown() throws InterruptedException {
    draining = false;
    drain.join(SECONDS.toMillis(5));
  }

  /** One claim for the whole run, so the count dips by the claim's width until the rest is back. */
  @Benchmark
  @Group("batched")
  @GroupThreads(1)
  public void batchedProducer(Blackhole bh) {
    bh.consume(queue.tryPutBatch(elements));
  }

  /** The measurement: an ordinary producer, alongside a batching one. */
  @Benchmark
  @Group("batched")
  @GroupThreads(3)
  public void neighbourOfBatch(Outcomes outcomes) {
    outcomes.attempts++;
    if (!queue.tryPut(ELEMENT)) {
      outcomes.refused++;
    }
  }

  /** The same admissions as {@link #batchedProducer}, one claim each: the control. */
  @Benchmark
  @Group("unbatched")
  @GroupThreads(1)
  public void loopingProducer(Blackhole bh) {
    for (int i = 0; i < batchSize; i++) {
      bh.consume(queue.tryPut(elements.get(i)));
    }
  }

  /** The same measurement, with nothing batching beside it. */
  @Benchmark
  @Group("unbatched")
  @GroupThreads(3)
  public void neighbourOfLoop(Outcomes outcomes) {
    outcomes.attempts++;
    if (!queue.tryPut(ELEMENT)) {
      outcomes.refused++;
    }
  }
}

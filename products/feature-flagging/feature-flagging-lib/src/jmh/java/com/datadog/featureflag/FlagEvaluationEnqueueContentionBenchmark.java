package com.datadog.featureflag;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import datadog.communication.BackendApiFactory;
import datadog.trace.api.Config;
import datadog.trace.api.featureflag.FeatureFlaggingGateway;
import datadog.trace.api.featureflag.flagevaluation.FlagEvalEvent;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.util.HashMap;
import java.util.Map;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Group;
import org.openjdk.jmh.annotations.GroupThreads;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Producer-contention benchmark for {@link FlagEvaluationWriterImpl#enqueue}.
 *
 * <p>Every flag evaluation in every application thread calls {@code enqueue}, so producer-side
 * scaling is the property that matters. {@link FlagEvaluationHotPathBenchmark#writerEnqueue}
 * measures the single-threaded cost and cannot see contention; this benchmark measures how enqueue
 * behaves as producer count grows.
 *
 * <p><strong>Why groups rather than {@code @Threads}:</strong> the hand-off queue is a JCTools
 * {@code mpscBlockingConsumerArrayQueue} - multi-producer, <em>single</em>-consumer. Calling {@code
 * poll()} from several threads breaks that contract, so producer threads cannot drain their own
 * events. Each group therefore pairs N producers with exactly one consumer.
 *
 * <p><strong>Why the consumer drains in batches:</strong> with more producers than consumers the
 * queue would otherwise saturate, after which {@code offer} fast-fails and the measurement turns
 * into {@code AtomicLong} contention on the overflow counter instead of the enqueue path. The
 * consumer polls up to {@link #DRAIN_BATCH} events per invocation to keep the queue off its
 * capacity limit. Overflow is reported at the end of each iteration so a saturated - and therefore
 * invalid - run is visible rather than silent.
 *
 * <p>Run: {@code ./gradlew :products:feature-flagging:feature-flagging-lib:jmh
 * -PjmhIncludes=FlagEvaluationEnqueueContentionBenchmark}.
 */
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2, timeUnit = SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = SECONDS)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(NANOSECONDS)
@Fork(value = 1)
public class FlagEvaluationEnqueueContentionBenchmark {

  /** Events the single consumer drains per invocation, so it can keep up with N producers. */
  static final int DRAIN_BATCH = 32;

  private static final int NUM_FLAGS = 100;
  private static final int NUM_USERS = 50;
  private static final int NUM_FIELDS = 10;

  private Map<String, Object> attrs;
  private String[] flagKeys;
  private String[] targetingKeys;
  private FlagEvaluationWriterImpl writer;

  /** Per-producer cursor: a shared counter would add its own cache-line contention. */
  @State(Scope.Thread)
  public static class ProducerCursor {
    int cursor;
  }

  @Setup(Level.Iteration)
  public void setUp() {
    // enqueue() no-ops unless the gateway gate is on; set it explicitly so the benchmark is not
    // silently measuring an early return.
    FeatureFlaggingGateway.setFlagEvaluationEnqueueEnabled(true);

    attrs = new HashMap<>();
    for (int i = 0; i < NUM_FIELDS; i++) {
      attrs.put("field" + i, "value");
    }
    flagKeys = keys("bench-flag-", NUM_FLAGS);
    targetingKeys = keys("bench-user-", NUM_USERS);

    final Config config = Config.get();
    final BackendApiFactory factory = new BackendApiFactory(config, null);
    // Capacity well above what the batch-draining consumer should ever let build up.
    writer = new FlagEvaluationWriterImpl(1 << 20, Long.MAX_VALUE, NANOSECONDS, factory, config);
  }

  /**
   * Reports queue overflow so a saturated run - where the consumer failed to keep up and the
   * numbers no longer describe the enqueue path - is visible in the benchmark output.
   */
  @org.openjdk.jmh.annotations.TearDown(Level.Iteration)
  @SuppressForbidden
  public void reportOverflow() {
    final long dropped = writer.droppedQueueOverflow();
    if (dropped > 0) {
      System.out.println(
          "\nWARNING: queue overflowed "
              + dropped
              + " times - consumer could not keep up, enqueue timings for this iteration are"
              + " measuring overflow accounting, not the enqueue path.");
    }
  }

  // ---- 1 producer: uncontended baseline ----

  @Benchmark
  @Group("producers1")
  @GroupThreads(1)
  public void enqueue1(final ProducerCursor c) {
    writer.enqueue(nextEvent(c));
  }

  @Benchmark
  @Group("producers1")
  @GroupThreads(1)
  public void drain1(final Blackhole blackhole) {
    drain(blackhole);
  }

  // ---- 4 producers ----

  @Benchmark
  @Group("producers4")
  @GroupThreads(4)
  public void enqueue4(final ProducerCursor c) {
    writer.enqueue(nextEvent(c));
  }

  @Benchmark
  @Group("producers4")
  @GroupThreads(1)
  public void drain4(final Blackhole blackhole) {
    drain(blackhole);
  }

  // ---- 16 producers ----

  @Benchmark
  @Group("producers16")
  @GroupThreads(16)
  public void enqueue16(final ProducerCursor c) {
    writer.enqueue(nextEvent(c));
  }

  @Benchmark
  @Group("producers16")
  @GroupThreads(1)
  public void drain16(final Blackhole blackhole) {
    drain(blackhole);
  }

  private void drain(final Blackhole blackhole) {
    for (int i = 0; i < DRAIN_BATCH; i++) {
      final FlagEvalEvent event = writer.pollQueuedEventForTest();
      if (event == null) {
        break;
      }
      blackhole.consume(event);
    }
  }

  private FlagEvalEvent nextEvent(final ProducerCursor c) {
    final int i = c.cursor++;
    return new FlagEvalEvent(
        flagKeys[Math.floorMod(i, flagKeys.length)],
        "variant-" + Math.floorMod(i, 4),
        "alloc-" + Math.floorMod(i, flagKeys.length),
        targetingKeys[Math.floorMod(i, targetingKeys.length)],
        null,
        1_700_000_000_000L + i,
        attrs);
  }

  private static String[] keys(final String prefix, final int count) {
    final String[] out = new String[count];
    for (int i = 0; i < count; i++) {
      out[i] = prefix + i;
    }
    return out;
  }
}

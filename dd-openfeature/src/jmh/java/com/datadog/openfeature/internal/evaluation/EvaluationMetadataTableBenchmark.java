package com.datadog.openfeature.internal.evaluation;

import static com.datadog.openfeature.internal.evaluation.MetadataKeys.EVAL_TIMESTAMP_MS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.datadog.openfeature.ExperimentationOptOutMode;
import com.datadog.openfeature.internal.ufc.ServerConfiguration;
import com.datadog.openfeature.internal.ufc.UniversalFlagConfigParser;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.ImmutableContext;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.Value;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Requests that each evaluate {@code flagsPerRequest} flags drawn from 1,000 enabled flags of the
 * 10,000-flag scale configuration ({@code evaluation-suites/scale}), for a subject and cohort that
 * change on every request, on threads sharing one evaluator. It sweeps the evaluation metadata
 * table size and time bucket width. {@code tableSize=0} builds the metadata of every evaluation, as
 * without the table; {@code timeBucketMs=0} leaves the timestamp out of the metadata.
 *
 * <p>{@code popularity=uniform} draws each flag of a request uniformly; {@code skewed} draws it
 * from a Zipf distribution with exponent 1, so a few flags take most evaluations. An operation is
 * one request, sampled for its mean and percentiles. Each measurement iteration prints the table's
 * hit rate, the share of successful evaluations that reused metadata, and how early the reported
 * {@code __dd_eval_timestamp_ms} was: the clock just before the evaluation minus the reported time,
 * sampled on every 8th request. Without a timestamp in the metadata, the flag evaluation hook reads
 * its own clock after the evaluation, so the reported time is never early.
 *
 * <p>Run with the thread count as an option: {@code java -jar dd-openfeature/build/libs/*-jmh.jar
 * EvaluationMetadataTableBenchmark -t 12 -prof gc}. {@code tableSize=0} ignores the bucket width.
 */
@State(Scope.Benchmark)
@Warmup(iterations = 2, time = 1, timeUnit = SECONDS)
@Measurement(iterations = 3, time = 1, timeUnit = SECONDS)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(NANOSECONDS)
@Fork(value = 1)
public class EvaluationMetadataTableBenchmark {
  private static final String CONFIG_PATH =
      "dd-openfeature/src/test/fixtures/ffe-system-test-data/evaluation-suites/scale/ufc-config.json";
  private static final int POPULATION = 1000;
  private static final int CONTEXTS = 1024;
  private static final int DRAWS = 1 << 16;
  private static final int ERROR_SAMPLE_MASK = 7;
  private static final int MAX_ERROR_MS = 10_000;

  /** The number of flags each request evaluates. */
  @Param({"10", "50"})
  public int flagsPerRequest;

  /** The requested table size, 0 for no table. */
  @Param({"0", "256", "1024", "4096"})
  public int tableSize;

  /** The time bucket width in milliseconds, 0 to leave the timestamp out of the metadata. */
  @Param({"1", "10", "100", "1000", "0"})
  public int timeBucketMs;

  /** How the flags of a request are drawn: {@code uniform} or {@code skewed} (Zipf). */
  @Param({"uniform", "skewed"})
  public String popularity;

  private ServerConfiguration config;
  private FlagEvaluator evaluator;
  private String[] flags;
  private double[] cumulative;
  private EvaluationContext[] contexts;
  private final AtomicInteger threadSeeds = new AtomicInteger();
  private final List<Requests> threads = Collections.synchronizedList(new ArrayList<>());
  private final LongAdder misses = new LongAdder();
  private final LongAdder lookups = new LongAdder();

  /** One benchmark thread: its flag draws, its positions, and its timestamp error histogram. */
  @State(Scope.Thread)
  public static class Requests {
    private int[] draws;
    private int nextDraw;
    private int nextContext;
    private int requests;
    private long[] earlyMs;

    @Setup(Level.Trial)
    public void setUp(final EvaluationMetadataTableBenchmark benchmark) {
      final int seed = benchmark.threadSeeds.incrementAndGet();
      draws = benchmark.draws(new SplittableRandom(seed));
      nextContext = (seed * 97) & (CONTEXTS - 1);
      earlyMs = new long[MAX_ERROR_MS + 1];
      benchmark.threads.add(this);
    }

    @Setup(Level.Iteration)
    public void reset() {
      Arrays.fill(earlyMs, 0);
    }

    int nextDraw() {
      final int draw = draws[nextDraw];
      nextDraw = (nextDraw + 1) & (DRAWS - 1);
      return draw;
    }

    EvaluationContext nextContext(final EvaluationContext[] contexts) {
      final EvaluationContext context = contexts[nextContext];
      nextContext = (nextContext + 1) & (CONTEXTS - 1);
      return context;
    }
  }

  @Setup(Level.Trial)
  public void setUp() throws IOException {
    config = UniversalFlagConfigParser.parse(Files.readAllBytes(configFile()));
    evaluator =
        new FlagEvaluator(
            false,
            (flag, subject, allocation, variant, serialId) -> false,
            ExperimentationOptOutMode.DISABLED,
            true,
            EvaluationMetadataTable.create(tableSize, misses),
            timeBucketMs);
    // Every 10th bulk flag is disabled: skip them, so each evaluation resolves a split.
    flags = new String[POPULATION];
    for (int i = 0, flag = 0; i < POPULATION; flag++) {
      if (flag % 10 != 9) {
        flags[i++] = String.format(Locale.ROOT, "scale-bulk-%05d", flag);
      }
    }
    cumulative = new double[POPULATION];
    double sum = 0;
    for (int i = 0; i < POPULATION; i++) {
      sum += "skewed".equals(popularity) ? 1.0 / (i + 1) : 1.0;
      cumulative[i] = sum;
    }
    for (int i = 0; i < POPULATION; i++) {
      cumulative[i] /= sum;
    }
    contexts = new EvaluationContext[CONTEXTS];
    for (int i = 0; i < CONTEXTS; i++) {
      contexts[i] =
          new ImmutableContext(
              "subject-" + i,
              Collections.singletonMap(
                  "cohort", new Value(String.format(Locale.ROOT, "cohort-%02d", i % 100))));
    }
    for (final String flag : flags) {
      final ProviderEvaluation<Boolean> result =
          evaluator.evaluate(config, Boolean.class, flag, false, contexts[0]);
      if (result.getErrorCode() != null || result.getVariant() == null) {
        throw new IllegalStateException(flag + " did not resolve a split: " + result.getReason());
      }
    }
  }

  int[] draws(final SplittableRandom random) {
    final int[] draws = new int[DRAWS];
    for (int i = 0; i < DRAWS; i++) {
      final int index = Arrays.binarySearch(cumulative, random.nextDouble());
      draws[i] = Math.min(index < 0 ? -index - 1 : index, POPULATION - 1);
    }
    return draws;
  }

  @Setup(Level.Iteration)
  public void startIteration() {
    misses.reset();
    lookups.reset();
  }

  @TearDown(Level.Iteration)
  @SuppressForbidden
  public void reportIteration() {
    final long total = lookups.sum();
    final long missed = tableSize == 0 ? total : misses.sum();
    final long[] early = new long[MAX_ERROR_MS + 1];
    synchronized (threads) {
      for (final Requests thread : threads) {
        for (int i = 0; i <= MAX_ERROR_MS; i++) {
          early[i] += thread.earlyMs[i];
        }
      }
    }
    long samples = 0;
    int max = 0;
    for (int i = 0; i <= MAX_ERROR_MS; i++) {
      samples += early[i];
      if (early[i] != 0) {
        max = i;
      }
    }
    int p99 = 0;
    for (long seen = early[0]; seen < samples * 0.99 && p99 < MAX_ERROR_MS; ) {
      seen += early[++p99];
    }
    System.out.printf(
        Locale.ROOT,
        "%n@@ table=%d bucket=%d flags=%d popularity=%s lookups=%d misses=%d hitRate=%.4f"
            + " earlyP99Ms=%d earlyMaxMs=%d samples=%d%n",
        EvaluationMetadataTable.size(tableSize),
        timeBucketMs,
        flagsPerRequest,
        popularity,
        total,
        missed,
        total == 0 ? 0 : (double) (total - missed) / total,
        p99,
        max,
        samples);
  }

  @Benchmark
  public void request(final Requests requests, final Blackhole blackhole) {
    final EvaluationContext context = requests.nextContext(contexts);
    final boolean sample = (requests.requests++ & ERROR_SAMPLE_MASK) == 0;
    for (int i = 0; i < flagsPerRequest; i++) {
      final String flag = flags[requests.nextDraw()];
      if (sample) {
        final long before = System.currentTimeMillis();
        final ProviderEvaluation<Boolean> result =
            evaluator.evaluate(config, Boolean.class, flag, false, context);
        final Long reported = result.getFlagMetadata().getLong(EVAL_TIMESTAMP_MS);
        // Without the timestamp, the hook reads its clock after the evaluation: never early.
        final long early = reported == null ? 0 : before - reported;
        requests.earlyMs[(int) Math.max(0, Math.min(early, MAX_ERROR_MS))]++;
        blackhole.consume(result);
      } else {
        blackhole.consume(evaluator.evaluate(config, Boolean.class, flag, false, context));
      }
    }
    lookups.add(flagsPerRequest);
  }

  private static Path configFile() {
    Path directory = Paths.get("").toAbsolutePath();
    while (directory != null) {
      final Path candidate = directory.resolve(CONFIG_PATH);
      if (Files.exists(candidate)) {
        return candidate;
      }
      directory = directory.getParent();
    }
    throw new IllegalStateException(
        "Unable to find the FFE scale suite: initialize the ffe-system-test-data submodule");
  }
}

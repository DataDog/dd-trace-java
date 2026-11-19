package com.datadog.openfeature.internal.evaluation;

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
import java.util.Collections;
import java.util.Locale;
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
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Concurrent requests that each evaluate the same {@code flagsPerRequest} flags of the 10,000-flag
 * scale configuration ({@code evaluation-suites/scale}), for a subject and cohort that change on
 * every request, on 12 threads sharing one evaluator. It shows what the evaluation metadata table
 * saves when many threads resolve the same splits in the same millisecond, and whether its slot
 * writes contend. {@code tableSize=0} builds the metadata of every evaluation, as without the
 * table.
 *
 * <p>An operation is one request. Each measurement iteration prints the table's hit rate (the share
 * of successful evaluations that reused metadata) and its misses per millisecond. The lowest
 * possible miss rate is one per distinct key per millisecond: a request resolves {@code
 * flagsPerRequest} splits, and a cohort can switch one of them, so about {@code flagsPerRequest +
 * 1} keys. Misses above that come from threads racing on the same slot in the same millisecond, or
 * from keys evicting each other.
 *
 * <p>Run: {@code ./gradlew :dd-openfeature:jmh -Pjmh.includes=EvaluationMetadataTableBenchmark
 * -Pjmh.profilers=gc}.
 */
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2, timeUnit = SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = SECONDS)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(NANOSECONDS)
@Threads(12)
@Fork(value = 1)
public class EvaluationMetadataTableBenchmark {
  private static final String CONFIG_PATH =
      "dd-openfeature/src/test/fixtures/ffe-system-test-data/evaluation-suites/scale/ufc-config.json";
  private static final int CONTEXTS = 1024;

  /** The number of different flags each request evaluates. */
  @Param({"10", "50"})
  public int flagsPerRequest;

  /** The requested table size, 0 for no table. */
  @Param({"0", "256", "1024", "4096"})
  public int tableSize;

  private ServerConfiguration config;
  private FlagEvaluator evaluator;
  private String[] flags;
  private EvaluationContext[] contexts;
  private final LongAdder misses = new LongAdder();
  private final LongAdder lookups = new LongAdder();
  private long iterationStart;

  /** The position of one benchmark thread in the contexts. */
  @State(Scope.Thread)
  public static class Requests {
    private int next;

    EvaluationContext next(final EvaluationContext[] contexts) {
      final EvaluationContext context = contexts[next];
      next = (next + 1) & (contexts.length - 1);
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
            EvaluationMetadataTable.create(tableSize, misses));
    // Every 10th bulk flag is disabled: skip them, so each evaluation resolves a split.
    flags = new String[flagsPerRequest];
    for (int i = 0, flag = 0; i < flagsPerRequest; flag++) {
      if (flag % 10 != 9) {
        flags[i++] = String.format(Locale.ROOT, "scale-bulk-%05d", flag);
      }
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

  @Setup(Level.Iteration)
  public void startIteration() {
    misses.reset();
    lookups.reset();
    iterationStart = System.nanoTime();
  }

  @TearDown(Level.Iteration)
  @SuppressForbidden
  public void reportIteration() {
    final double elapsedMs = (System.nanoTime() - iterationStart) / 1e6;
    final long total = lookups.sum();
    final long missed = tableSize == 0 ? total : misses.sum();
    System.out.printf(
        Locale.ROOT,
        "%n[table %d, %d flags] lookups %d, misses %d, hit rate %.4f%%, misses/ms %.1f%n",
        EvaluationMetadataTable.size(tableSize),
        flagsPerRequest,
        total,
        missed,
        total == 0 ? 0 : 100.0 * (total - missed) / total,
        missed / elapsedMs);
  }

  @Benchmark
  public void request(final Requests requests, final Blackhole blackhole) {
    final EvaluationContext context = requests.next(contexts);
    for (final String flag : flags) {
      blackhole.consume(evaluator.evaluate(config, Boolean.class, flag, false, context));
    }
    lookups.add(flags.length);
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

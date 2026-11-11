package com.datadog.openfeature;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import de.thetaphi.forbiddenapis.SuppressForbidden;
import dev.openfeature.sdk.ImmutableContext;
import dev.openfeature.sdk.LayeredEvaluationContext;
import dev.openfeature.sdk.ProviderEvaluation;
import java.io.IOException;
import java.util.List;
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

/**
 * Evaluation cost on the opt-in scale suites of the shared FFE fixtures ({@code
 * evaluation-suites/scale} and {@code evaluation-suites/scale-json}): large lists, regex, rules,
 * splits and shards, time windows, many variations and flags, and JSON values up to 4 MB. The
 * dimensions are listed in {@code evaluation-suites/scale/AGENTS.md} of the fixtures.
 *
 * <p>Each operation evaluates a case of the selected case file through the provider. With {@code
 * index=all}, each benchmark thread goes through the cases of the file in turn, so a row is the
 * unweighted mean over every case of the file, and its slowest case dominates it. Select one case
 * to measure one shape: the setup prints the index of each case.
 *
 * <p>The setup evaluates every case once and the teardown again after the measurement. Both fail if
 * a value, reason, or expected selection metadata (serial ID, {@code doLog}, split features)
 * differs from the expected one, or if an evaluation reports an error, so a run that resolves a
 * case wrongly fails instead of reporting a number. JSON values are compared by numeric value: a
 * JSON integer can come back as an integer or as a double. This check guards the measurement and is
 * not the conformance check: {@code CanonicalFixtureTest} checks the types.
 *
 * <p>The provider is called directly: the OpenFeature client, its hooks and exposure logging are
 * not measured. {@code evaluate} passes a reused context. {@code evaluateLayered} passes a fresh
 * {@code LayeredEvaluationContext} around it on each call, as the client does, so it also shows the
 * cost of reading attributes through the client's context, such as a rule on {@code id} when the
 * context has no {@code id}. Select one method with {@code
 * -Pjmh.includes=ScaleSuiteBenchmark.evaluate$}.
 *
 * <p>The cases and their check are shared with {@code ScaleSuiteTest}, which runs them in the unit
 * tests. One fork is enough for allocation and for large time differences. For a time difference
 * under about 15%, run three forks: {@code -Pjmh.forks=3} or {@code -f 3}.
 *
 * <p>Run all case files: {@code ./gradlew :dd-openfeature:jmh -Pjmh.includes=ScaleSuiteBenchmark
 * -Pjmh.profilers=gc}. Run one case: {@code ./gradlew :dd-openfeature:jmhJar}, then {@code java
 * -jar dd-openfeature/build/libs/*-jmh.jar ScaleSuiteBenchmark -p cases=scale/splits -p index=0
 * -prof gc}.
 */
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2, timeUnit = SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = SECONDS)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(NANOSECONDS)
@Fork(value = 1)
public class ScaleSuiteBenchmark {

  /**
   * A case file, as {@code <suite>/<file>} without the {@code .json} extension. {@code
   * ScaleSuiteTest} reads this list and fails unless it names every case file of the fixtures, in
   * sorted order.
   */
  @Param({
    "scale/flags",
    "scale/numeric",
    "scale/one-of",
    "scale/one-of-many",
    "scale/one-of-small-numbers",
    "scale/regex",
    "scale/regex-many",
    "scale/rules",
    "scale/rules-sweep",
    "scale/semver",
    "scale/splits",
    "scale/time-windows",
    "scale/variations",
    "scale/wide-context",
    "scale-json/json-shapes",
    "scale-json/json-sizes",
    "scale-json/json-values"
  })
  public String cases;

  /** The index of one case in the file, or {@code all} to go through every case in turn. */
  @Param({"all"})
  public String index;

  private Provider provider;
  private ScaleCase[] scaleCases;

  /** The position of one benchmark thread in the cases, so threads share no mutable state. */
  @State(Scope.Thread)
  public static class Cursor {
    private int next;

    ScaleCase next(final ScaleCase[] scaleCases) {
      final ScaleCase scaleCase = scaleCases[next];
      next = (next + 1 == scaleCases.length) ? 0 : next + 1;
      return scaleCase;
    }
  }

  @Setup(Level.Trial)
  @SuppressForbidden
  public void setUp() throws IOException {
    final int slash = cases.indexOf('/');
    final ScaleSuites.Suite suite = ScaleSuites.load(cases.substring(0, slash));
    provider = suite.provider();
    final List<ScaleCase> loaded = suite.cases(cases.substring(slash + 1));
    for (int i = 0; i < loaded.size(); i++) {
      final ScaleCase scaleCase = loaded.get(i);
      scaleCase.check(scaleCase.evaluate(provider));
      System.out.println("[" + cases + "] case " + i + ": " + scaleCase.describe());
    }
    scaleCases =
        "all".equals(index)
            ? loaded.toArray(new ScaleCase[0])
            : new ScaleCase[] {loaded.get(Integer.parseInt(index))};
  }

  @TearDown(Level.Trial)
  public void tearDown() {
    for (final ScaleCase scaleCase : scaleCases) {
      scaleCase.check(scaleCase.evaluate(provider));
    }
    provider.shutdown();
  }

  @Benchmark
  public ProviderEvaluation<?> evaluate(final Cursor cursor) {
    return cursor.next(scaleCases).evaluate(provider);
  }

  /** As {@link #evaluate}, with the fresh layered context that the OpenFeature client builds. */
  @Benchmark
  public ProviderEvaluation<?> evaluateLayered(final Cursor cursor) {
    final ScaleCase scaleCase = cursor.next(scaleCases);
    return scaleCase.evaluate(
        provider,
        new LayeredEvaluationContext(
            ImmutableContext.EMPTY, ImmutableContext.EMPTY, null, scaleCase.context()));
  }
}

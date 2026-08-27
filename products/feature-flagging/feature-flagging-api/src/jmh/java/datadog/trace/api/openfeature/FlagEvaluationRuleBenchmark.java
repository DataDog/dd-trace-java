package datadog.trace.api.openfeature;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import datadog.trace.api.featureflag.ufc.v1.Allocation;
import datadog.trace.api.featureflag.ufc.v1.ConditionConfiguration;
import datadog.trace.api.featureflag.ufc.v1.ConditionOperator;
import datadog.trace.api.featureflag.ufc.v1.Flag;
import datadog.trace.api.featureflag.ufc.v1.Rule;
import datadog.trace.api.featureflag.ufc.v1.ServerConfiguration;
import datadog.trace.api.featureflag.ufc.v1.Shard;
import datadog.trace.api.featureflag.ufc.v1.ShardRange;
import datadog.trace.api.featureflag.ufc.v1.Split;
import datadog.trace.api.featureflag.ufc.v1.ValueType;
import datadog.trace.api.featureflag.ufc.v1.Variant;
import dev.openfeature.sdk.MutableContext;
import java.util.List;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Measures evaluator rule costs that run on the application thread.
 *
 * <p>The static case is the control. The regex and shard cases differ only in the rule work that
 * selects the same boolean variation.
 *
 * <p>Run: {@code ./gradlew :products:feature-flagging:feature-flagging-api:jmh
 * -PjmhIncludes=FlagEvaluationRuleBenchmark -PjmhProf=gc}.
 */
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2, timeUnit = SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = SECONDS)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(NANOSECONDS)
@Fork(1)
public class FlagEvaluationRuleBenchmark {

  private DDEvaluator staticEvaluator;
  private DDEvaluator regexEvaluator;
  private DDEvaluator shardEvaluator;
  private MutableContext context;

  @Setup
  public void setUp() {
    context = new MutableContext("benchmark-subject-0123456789");
    context.add("email", "benchmark@datadoghq.com");

    staticEvaluator = evaluator(staticAllocation());
    regexEvaluator = evaluator(regexAllocation());
    shardEvaluator = evaluator(shardAllocation());
  }

  @Benchmark
  public void staticRule(final Blackhole blackhole) {
    blackhole.consume(staticEvaluator.evaluate(Boolean.class, "bench", false, context));
  }

  @Benchmark
  public void regexRule(final Blackhole blackhole) {
    blackhole.consume(regexEvaluator.evaluate(Boolean.class, "bench", false, context));
  }

  @Benchmark
  public void shardRule(final Blackhole blackhole) {
    blackhole.consume(shardEvaluator.evaluate(Boolean.class, "bench", false, context));
  }

  private static DDEvaluator evaluator(final Allocation allocation) {
    final Flag flag =
        new Flag(
            "bench",
            true,
            ValueType.BOOLEAN,
            singletonMap("on", new Variant("on", true)),
            singletonList(allocation));
    final DDEvaluator evaluator = new DDEvaluator(() -> {});
    evaluator.accept(new ServerConfiguration("", "", false, null, singletonMap("bench", flag)));
    return evaluator;
  }

  private static Allocation staticAllocation() {
    return allocation(emptyList(), new Split(emptyList(), "on", null, null));
  }

  private static Allocation regexAllocation() {
    final ConditionConfiguration condition =
        new ConditionConfiguration(
            ConditionOperator.MATCHES, "email", "^[[:alnum:]._%+-]+@datadoghq[.]com$");
    condition.cacheRegexPattern();
    return allocation(singletonList(new Rule(singletonList(condition))), staticSplit());
  }

  private static Allocation shardAllocation() {
    final Shard shard =
        new Shard("benchmark-allocation-salt", singletonList(new ShardRange(0, 100_000)), 100_000);
    return allocation(emptyList(), new Split(singletonList(shard), "on", null, null));
  }

  private static Split staticSplit() {
    return new Split(emptyList(), "on", null, null);
  }

  private static Allocation allocation(final List<Rule> rules, final Split split) {
    return new Allocation(
        "benchmark-allocation", rules, null, null, singletonList(split), Boolean.FALSE);
  }
}

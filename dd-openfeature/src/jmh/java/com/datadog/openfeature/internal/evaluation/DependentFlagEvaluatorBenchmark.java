package com.datadog.openfeature.internal.evaluation;

import static com.datadog.openfeature.internal.ufc.ConditionOperator.ONE_OF;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.datadog.openfeature.internal.ufc.Allocation;
import com.datadog.openfeature.internal.ufc.ConditionConfiguration;
import com.datadog.openfeature.internal.ufc.Flag;
import com.datadog.openfeature.internal.ufc.Rule;
import com.datadog.openfeature.internal.ufc.ServerConfiguration;
import com.datadog.openfeature.internal.ufc.Split;
import com.datadog.openfeature.internal.ufc.ValueType;
import com.datadog.openfeature.internal.ufc.Variant;
import dev.openfeature.sdk.MutableContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import org.openjdk.jmh.annotations.Warmup;

/** Measures exposure-trace accumulation across wide dependent-flag graphs. */
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1, timeUnit = SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = SECONDS)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(NANOSECONDS)
@Fork(1)
public class DependentFlagEvaluatorBenchmark {
  @Param({"16", "64", "256"})
  public int dependencyCount;

  private FlagEvaluator evaluator;
  private ServerConfiguration configuration;
  private ServerConfiguration sharedSubgraphConfiguration;
  private MutableContext context;

  @Setup
  public void setUp() {
    evaluator = new FlagEvaluator(false, (flag, subject, allocation, variant, serialId) -> false);
    context = new MutableContext("benchmark-subject");
    final Map<String, Flag> flags = new HashMap<>();
    final List<ConditionConfiguration> conditions = new ArrayList<>();
    for (int i = 0; i < dependencyCount; i++) {
      final String key = "dependency-" + i;
      flags.put(key, flag(key, null));
      conditions.add(new ConditionConfiguration(ONE_OF, null, key, singletonList("on")));
    }
    flags.put("root", flag("root", new Rule(conditions)));
    configuration = new ServerConfiguration("", "", false, null, flags);
    sharedSubgraphConfiguration = sharedSubgraphConfiguration(dependencyCount);
  }

  @Benchmark
  public FlagEvaluator.EvaluationResult<String> evaluateWideGraph() {
    return evaluator.evaluateWithTrace(configuration, String.class, "root", "default", context);
  }

  @Benchmark
  public FlagEvaluator.EvaluationResult<String> evaluateSharedSubgraph() {
    return evaluator.evaluateWithTrace(
        sharedSubgraphConfiguration, String.class, "root", "default", context);
  }

  private static ServerConfiguration sharedSubgraphConfiguration(final int width) {
    final Map<String, Flag> flags = new HashMap<>();
    final List<ConditionConfiguration> leafConditions = new ArrayList<>();
    for (int i = 0; i < width; i++) {
      final String leaf = "leaf-" + i;
      flags.put(leaf, flag(leaf, null));
      leafConditions.add(new ConditionConfiguration(ONE_OF, null, leaf, singletonList("on")));
    }
    flags.put("hub", flag("hub", new Rule(leafConditions)));
    final List<ConditionConfiguration> parentConditions = new ArrayList<>();
    for (int i = 0; i < width; i++) {
      final String parent = "parent-" + i;
      flags.put(
          parent,
          flag(
              parent,
              new Rule(
                  singletonList(
                      new ConditionConfiguration(ONE_OF, null, "hub", singletonList("on"))))));
      parentConditions.add(new ConditionConfiguration(ONE_OF, null, parent, singletonList("on")));
    }
    flags.put("root", flag("root", new Rule(parentConditions)));
    return new ServerConfiguration("", "", false, null, flags);
  }

  private static Flag flag(final String key, final Rule rule) {
    final Map<String, Variant> variants = new HashMap<>();
    variants.put("on", new Variant("on", "on-value"));
    final Allocation allocation =
        new Allocation(
            key + "-allocation",
            rule == null ? null : singletonList(rule),
            null,
            null,
            singletonList(new Split(emptyList(), "on", emptyMap(), 1)),
            false);
    return new Flag(key, true, ValueType.STRING, variants, singletonList(allocation));
  }
}

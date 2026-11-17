package com.datadog.openfeature;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.datadog.openfeature.internal.bridge.v1.AgentBridge;
import com.datadog.openfeature.internal.config.Settings;
import com.datadog.openfeature.internal.ufc.Allocation;
import com.datadog.openfeature.internal.ufc.Flag;
import com.datadog.openfeature.internal.ufc.ServerConfiguration;
import com.datadog.openfeature.internal.ufc.Split;
import com.datadog.openfeature.internal.ufc.ValueType;
import com.datadog.openfeature.internal.ufc.Variant;
import dev.openfeature.sdk.ImmutableContext;
import dev.openfeature.sdk.LayeredEvaluationContext;
import dev.openfeature.sdk.MutableContext;
import dev.openfeature.sdk.ProviderEvaluation;
import java.util.HashMap;
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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Measures provider resolution, including timestamp validation and ordinary allocation fallback.
 */
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1, timeUnit = SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = SECONDS)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(NANOSECONDS)
@Fork(1)
public class ExperimentationOptOutBenchmark {
  @Param({"DISABLED", "IMMEDIATE", "FUTURE_EXPERIMENTS_ONLY"})
  public ExperimentationOptOutMode mode;

  @Param({"false", "true"})
  public boolean optedOut;

  @Param({"0", "16"})
  public int extraAttributes;

  private Provider provider;
  private MutableContext context;

  @Setup
  public void setUp() {
    final DDEvaluator evaluator =
        new DDEvaluator(() -> {}, AgentBridge.NONE, Settings.load(AgentBridge.NONE), mode);
    final Map<String, Variant> variants = new HashMap<>();
    variants.put("experiment", new Variant("experiment", false));
    variants.put("ordinary", new Variant("ordinary", true));
    final Flag flag =
        new Flag(
            "flag",
            true,
            ValueType.BOOLEAN,
            variants,
            asList(allocation("experiment", true), allocation("ordinary", false)));
    evaluator.accept(new ServerConfiguration("", "", false, null, singletonMap("flag", flag)));
    provider =
        new Provider(new Provider.Options().experimentationOptOutMode(mode), evaluator, false);
    context = new MutableContext("subject");
    for (int i = 0; i < extraAttributes; i++) {
      context.add("attribute" + i, "value" + i);
    }
    if (optedOut) {
      context.add("dd_experimentation_opt_out_at", "2026-10-19T12:00:00Z");
    }
    final ProviderEvaluation<Boolean> result = evaluate();
    final boolean excluded = mode != ExperimentationOptOutMode.DISABLED && optedOut;
    if (result.getErrorCode() != null
        || result.getValue() != excluded
        || !(excluded ? "ordinary" : "experiment")
            .equals(result.getFlagMetadata().getString("allocationKey"))) {
      throw new IllegalStateException("Benchmark did not resolve the expected allocation");
    }
  }

  private static Allocation allocation(final String key, final boolean doLog) {
    return new Allocation(
        key, null, null, null, singletonList(new Split(emptyList(), key, emptyMap(), 1)), doLog);
  }

  @Benchmark
  public ProviderEvaluation<Boolean> evaluate() {
    return provider.getBooleanEvaluation("flag", false, context);
  }

  @Benchmark
  public ProviderEvaluation<Boolean> evaluateFreshLayeredContext() {
    return provider.getBooleanEvaluation(
        "flag",
        false,
        new LayeredEvaluationContext(
            ImmutableContext.EMPTY, ImmutableContext.EMPTY, null, context));
  }

  @TearDown
  public void tearDown() {
    provider.shutdown();
  }
}

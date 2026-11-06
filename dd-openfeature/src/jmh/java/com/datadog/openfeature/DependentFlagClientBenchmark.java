package com.datadog.openfeature;

import static com.datadog.openfeature.internal.ufc.ConditionOperator.ONE_OF;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.datadog.openfeature.internal.bridge.v1.AgentBridge;
import com.datadog.openfeature.internal.config.Settings;
import com.datadog.openfeature.internal.exposure.ExposureDeduplicationCache;
import com.datadog.openfeature.internal.exposure.ExposureEvent;
import com.datadog.openfeature.internal.ufc.Allocation;
import com.datadog.openfeature.internal.ufc.ConditionConfiguration;
import com.datadog.openfeature.internal.ufc.Flag;
import com.datadog.openfeature.internal.ufc.Rule;
import com.datadog.openfeature.internal.ufc.ServerConfiguration;
import com.datadog.openfeature.internal.ufc.Split;
import com.datadog.openfeature.internal.ufc.ValueType;
import com.datadog.openfeature.internal.ufc.Variant;
import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.MutableContext;
import dev.openfeature.sdk.OpenFeatureAPI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
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

/** Profiles the real OpenFeature client/hook path for ordinary and dependent roots. */
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1, timeUnit = SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = SECONDS)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(MICROSECONDS)
@Fork(1)
public class DependentFlagClientBenchmark {
  @Param({"0", "32"})
  public int contextAttributes;

  private final BenchmarkEvaluator evaluator = new BenchmarkEvaluator();
  private final OpenFeatureAPI api = OpenFeatureAPI.getInstance();
  private Client client;
  private MutableContext context;

  @Setup(Level.Trial)
  public void setUp() throws Exception {
    api.clearHooks();
    api.shutdown();
    evaluator.accept(configuration(16));
    api.setProviderAndWait(
        new Provider(
            new Provider.Options().enableEvaluationTelemetryOptOutParameter(false),
            evaluator,
            false));
    client = api.getClient();
    context = new MutableContext("benchmark-subject");
    for (int i = 0; i < contextAttributes; i++) {
      context.add("attribute-" + i, "value-" + i);
    }
    if (ordinaryClientEvaluation().getErrorCode() != null
        || dependentExposureHook().getErrorCode() != null) {
      throw new IllegalStateException("benchmark configuration did not evaluate successfully");
    }
  }

  @Setup(Level.Invocation)
  public void clearExposureCache() {
    evaluator.cache.clear();
  }

  @Benchmark
  public FlagEvaluationDetails<Integer> ordinaryClientEvaluation() {
    return client.getIntegerDetails("ordinary", 0, context);
  }

  /** Includes returned-batch publication and all dependency exposure hooks after cache misses. */
  @Benchmark
  public FlagEvaluationDetails<Integer> dependentExposureHook() {
    return client.getIntegerDetails("root", 0, context);
  }

  @TearDown(Level.Trial)
  public void tearDown() {
    api.clearHooks();
    api.shutdown();
  }

  private static ServerConfiguration configuration(final int dependencyCount) {
    final Map<String, Flag> flags = new HashMap<>();
    flags.put("ordinary", ordinaryFlagWithUnreachableRules(128));
    final List<ConditionConfiguration> conditions = new ArrayList<>();
    for (int i = 0; i < dependencyCount; i++) {
      final String key = "dependency-" + i;
      flags.put(key, stringFlag(key));
      conditions.add(new ConditionConfiguration(ONE_OF, null, key, singletonList("on")));
    }
    flags.put("root", integerFlag("root", new Rule(conditions), false));
    return new ServerConfiguration("", "", false, null, flags);
  }

  private static Flag ordinaryFlagWithUnreachableRules(final int ruleCount) {
    final Map<String, Variant> variants = new HashMap<>();
    variants.put("selected", new Variant("selected", 1));
    final List<Allocation> allocations = new ArrayList<>();
    for (int i = 0; i < ruleCount; i++) {
      final ConditionConfiguration condition =
          new ConditionConfiguration(ONE_OF, "missing-" + i, singletonList("match"));
      allocations.add(
          new Allocation(
              "unreachable-" + i,
              singletonList(new Rule(singletonList(condition))),
              null,
              null,
              singletonList(new Split(emptyList(), "selected", emptyMap(), 1)),
              false));
    }
    allocations.add(
        new Allocation(
            "selected-allocation",
            null,
            null,
            null,
            singletonList(new Split(emptyList(), "selected", emptyMap(), 1)),
            false));
    return new Flag("ordinary", true, ValueType.INTEGER, variants, allocations);
  }

  private static Flag integerFlag(final String key, final Rule rule, final boolean doLog) {
    final Map<String, Variant> variants = new HashMap<>();
    variants.put("selected", new Variant("selected", 1));
    return new Flag(
        key,
        true,
        ValueType.INTEGER,
        variants,
        singletonList(
            new Allocation(
                key + "-allocation",
                rule == null ? null : singletonList(rule),
                null,
                null,
                singletonList(new Split(emptyList(), "selected", emptyMap(), 1)),
                doLog)));
  }

  private static Flag stringFlag(final String key) {
    final Map<String, Variant> variants = new HashMap<>();
    variants.put("on", new Variant("on", "on-value"));
    return new Flag(
        key,
        true,
        ValueType.STRING,
        variants,
        singletonList(
            new Allocation(
                key + "-allocation",
                null,
                null,
                null,
                singletonList(new Split(emptyList(), "on", emptyMap(), 1)),
                true)));
  }

  private static final class BenchmarkEvaluator extends DDEvaluator {
    private final ExposureDeduplicationCache cache = new ExposureDeduplicationCache();
    private volatile int exposureCount;

    private BenchmarkEvaluator() {
      super(() -> {}, AgentBridge.NONE, Settings.load(AgentBridge.NONE));
    }

    @Override
    public boolean initialize(
        final long timeout, final TimeUnit unit, final EvaluationContext evaluationContext) {
      return hasConfiguration();
    }

    @Override
    ExposureDeduplicationCache exposureCache() {
      return cache;
    }

    @Override
    boolean isExposureDeliveryEnabled() {
      return true;
    }

    @Override
    void recordExposure(final ExposureEvent event) {
      exposureCount++;
    }
  }
}

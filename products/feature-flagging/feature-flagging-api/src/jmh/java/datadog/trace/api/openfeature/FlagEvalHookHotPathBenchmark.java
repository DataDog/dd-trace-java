package datadog.trace.api.openfeature;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import datadog.trace.api.featureflag.FeatureFlaggingGateway;
import datadog.trace.api.featureflag.flagevaluation.FlagEvalEvent;
import datadog.trace.api.featureflag.flagevaluation.FlagEvaluationWriter;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.FlagValueType;
import dev.openfeature.sdk.HookContext;
import dev.openfeature.sdk.ImmutableMetadata;
import dev.openfeature.sdk.ImmutableStructure;
import dev.openfeature.sdk.MutableContext;
import dev.openfeature.sdk.Reason;
import dev.openfeature.sdk.Value;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * Evaluation-thread benchmark for the OpenFeature hook: the inline cost a caller actually pays
 * inside FlagEvalLoggingHook.finallyAfter, across context shapes.
 *
 * <p>This is the counterpart to FlagEvaluationHotPathBenchmark in feature-flagging-lib, which
 * measures the writer queue and the worker-thread aggregation. The hook and the OpenFeature context
 * types live in this module, so the inline cost has to be measured here.
 *
 * <p>The dominant inline cost under consent-on is DDEvaluator.copyPrunedContext: one bounded walk
 * of the caller-owned EvaluationContext. contextCopy isolates that walk so its share of
 * hookFinallyAfter is directly readable. Under consent-off the hook skips the copy entirely, which
 * hookFinallyAfterConsentOff measures as the protected-path floor.
 *
 * <p>The writer used here is a no-op that discards events, so no queue or worker cost is included.
 *
 * <p>Run: {@code ./gradlew :products:feature-flagging:feature-flagging-api:jmh
 * -PjmhIncludes=FlagEvalHookHotPathBenchmark}.
 */
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2, timeUnit = SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = SECONDS)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(NANOSECONDS)
@Fork(value = 1)
public class FlagEvalHookHotPathBenchmark {

  /**
   * Context shapes. The three 100-leaf shapes (flat/100attrs, nested/10structs_10fields,
   * list/10lists_10items) carry the same leaf count under different structure, so the spread
   * between them isolates shape cost from leaf count.
   */
  @Param({
    "flat/0attrs",
    "flat/10attrs",
    "flat/100attrs",
    "nested/10structs_10fields",
    "list/10lists_10items"
  })
  public String shape;

  private HookContext<Object> hookContext;
  private FlagEvaluationDetails<Object> consentOnDetails;
  private FlagEvaluationDetails<Object> consentOffDetails;
  private FlagEvalLoggingHook<Object> hook;

  @Setup(Level.Trial)
  public void setUp() {
    final MutableContext ctx = buildContext(shape);
    hookContext =
        HookContext.builder()
            .flagKey("bench-flag")
            .type(FlagValueType.STRING)
            .defaultValue("default")
            .ctx(ctx)
            .build();

    consentOnDetails = details(true);
    consentOffDetails = details(false);

    // Discarding writer: isolates hook-inline cost from queue mechanics.
    hook = new FlagEvalLoggingHook<>(new NoOpWriter());
    FeatureFlaggingGateway.setFlagEvaluationEnqueueEnabled(true);
  }

  @TearDown(Level.Trial)
  public void tearDown() {
    FeatureFlaggingGateway.setFlagEvaluationEnqueueEnabled(false);
  }

  /** Total inline cost under consent-on: scalar extraction, bounded context copy, and enqueue. */
  @Benchmark
  public void hookFinallyAfter() {
    hook.finallyAfter(hookContext, consentOnDetails, Collections.emptyMap());
  }

  /** Protected-path floor: consent-off skips the context copy, leaving scalar work plus enqueue. */
  @Benchmark
  public void hookFinallyAfterConsentOff() {
    hook.finallyAfter(hookContext, consentOffDetails, Collections.emptyMap());
  }

  /** The bounded context copy alone - the component that scales with context shape. */
  @Benchmark
  public void contextCopy(final Blackhole blackhole) {
    blackhole.consume(DDEvaluator.copyPrunedContext(hookContext.getCtx()));
  }

  private static FlagEvaluationDetails<Object> details(final boolean observeFullEvaluationData) {
    return FlagEvaluationDetails.builder()
        .flagKey("bench-flag")
        .value("on-value")
        .variant("on")
        .reason(Reason.TARGETING_MATCH.name())
        .flagMetadata(
            ImmutableMetadata.builder()
                .addString("allocationKey", "alloc-1")
                .addLong("__dd_eval_timestamp_ms", 1_700_000_000_000L)
                .addBoolean(
                    DDEvaluator.METADATA_OBSERVE_FULL_EVALUATION_DATA, observeFullEvaluationData)
                .build())
        .build();
  }

  private static MutableContext buildContext(final String shape) {
    final MutableContext ctx = new MutableContext("bench-user");
    if ("flat/0attrs".equals(shape)) {
      return ctx;
    }
    if ("flat/10attrs".equals(shape)) {
      return addFlat(ctx, 10);
    }
    if ("flat/100attrs".equals(shape)) {
      return addFlat(ctx, 100);
    }
    if ("nested/10structs_10fields".equals(shape)) {
      for (int i = 0; i < 10; i++) {
        final Map<String, Value> inner = new HashMap<>();
        for (int j = 0; j < 10; j++) {
          inner.put("field" + j, new Value("value" + j));
        }
        ctx.add("struct" + i, new ImmutableStructure(inner));
      }
      return ctx;
    }
    if ("list/10lists_10items".equals(shape)) {
      for (int i = 0; i < 10; i++) {
        final List<Value> items = new ArrayList<>(10);
        for (int j = 0; j < 10; j++) {
          items.add(new Value("value" + j));
        }
        ctx.add("list" + i, items);
      }
      return ctx;
    }
    throw new IllegalArgumentException("unknown benchmark shape: " + shape);
  }

  private static MutableContext addFlat(final MutableContext ctx, final int count) {
    for (int i = 0; i < count; i++) {
      ctx.add("field" + i, "value" + i);
    }
    return ctx;
  }

  /** Discards events so only hook-inline work is measured. */
  private static final class NoOpWriter implements FlagEvaluationWriter {
    @Override
    public void enqueue(final FlagEvalEvent event) {}

    @Override
    public boolean hasCapacityForEnqueue() {
      return true;
    }

    @Override
    public void countPreQueueOverflow() {}

    @Override
    public void countContextTruncated(final String reason) {}

    @Override
    public void start() {}

    @Override
    public void close() {}
  }
}

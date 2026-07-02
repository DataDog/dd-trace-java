package com.datadog.featureflag;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import datadog.communication.BackendApiFactory;
import datadog.trace.api.Config;
import datadog.trace.api.featureflag.flagevaluation.FlagEvalEvent;
import java.util.HashMap;
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
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Hot-path benchmark for EVP {@code flagevaluation} recording.
 *
 * <p>The OpenFeature {@code finally} hook runs synchronously on the caller's evaluation thread. The
 * evaluation thread should only pay for scalar capture, lazy context supplier capture, and the
 * non-blocking writer enqueue. Recursive context flattening, pruning, canonical-key construction,
 * and aggregation are characterized separately as worker-thread cost.
 *
 * <p>Run: {@code ./gradlew :products:feature-flagging:feature-flagging-lib:jmh
 * -PjmhIncludes=FlagEvaluationHotPathBenchmark}.
 */
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2, timeUnit = SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = SECONDS)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(NANOSECONDS)
@Fork(value = 1)
public class FlagEvaluationHotPathBenchmark {

  @Param({
    "typical/100flags_50users_10fields",
    "stress/10flags_1000users_250fields",
    "scale/2500flags_500users_20fields"
  })
  public String profile;

  private Map<String, Object> attrs;
  private String[] flagKeys;
  private String[] targetingKeys;
  private int cursor;
  private FlagEvaluationWriterImpl writer;
  private FlagEvaluationWriterImpl.SerializingHandlerForTest handler;

  @Setup(Level.Iteration)
  public void setUp() {
    final Profile p = Profile.fromName(profile);

    attrs = new HashMap<>();
    for (int i = 0; i < p.numFields; i++) {
      attrs.put("field" + i, "value");
    }
    flagKeys = keys("bench-flag-", p.numFlags);
    targetingKeys = keys("bench-user-", p.numUsers);
    cursor = 0;

    final Config config = Config.get();
    final BackendApiFactory factory = new BackendApiFactory(config, null);
    final Map<String, String> ddContext = new HashMap<>();
    ddContext.put("service", "bench-service");
    handler = FlagEvaluationWriterImpl.createHandlerForTest(factory, ddContext);

    // Capacity large enough that the benchmark never overflows within a measurement window.
    writer = new FlagEvaluationWriterImpl(1 << 20, Long.MAX_VALUE, NANOSECONDS, factory, config);
  }

  /** Evaluation-thread cost: lazy capture snapshot plus non-blocking enqueue. */
  @Benchmark
  public void evalThreadCapture(final Blackhole blackhole) {
    final FlagEvalEvent event = nextLazyEvent();
    writer.enqueue(event);
    blackhole.consume(writer.pollQueuedEventForTest());
    blackhole.consume(event);
  }

  /** Worker-thread cost: materialize context, prune, canonicalize, and aggregate. */
  @Benchmark
  public void workerAggregate(final Blackhole blackhole) {
    final FlagEvalEvent event = nextLazyEvent();
    handler.aggregateEvent(event);
    if ((cursor % 10_000) == 0) {
      handler.clearAggregationForTest();
    }
    blackhole.consume(handler.fullTierSizeForTest());
  }

  private FlagEvalEvent nextLazyEvent() {
    final int i = cursor++;
    return new FlagEvalEvent(
        flagKeys[Math.floorMod(i, flagKeys.length)],
        "variant-" + Math.floorMod(i, 4),
        "alloc-" + Math.floorMod(i, flagKeys.length),
        targetingKeys[Math.floorMod(i, targetingKeys.length)],
        null,
        1_700_000_000_000L + i,
        () -> attrs);
  }

  private static String[] keys(final String prefix, final int count) {
    final String[] out = new String[count];
    for (int i = 0; i < count; i++) {
      out[i] = prefix + i;
    }
    return out;
  }

  private static final class Profile {
    private final int numFlags;
    private final int numUsers;
    private final int numFields;

    private Profile(final int numFlags, final int numUsers, final int numFields) {
      this.numFlags = numFlags;
      this.numUsers = numUsers;
      this.numFields = numFields;
    }

    private static Profile fromName(final String name) {
      if ("typical/100flags_50users_10fields".equals(name)) {
        return new Profile(100, 50, 10);
      }
      if ("stress/10flags_1000users_250fields".equals(name)) {
        return new Profile(10, 1_000, 250);
      }
      if ("scale/2500flags_500users_20fields".equals(name)) {
        return new Profile(2_500, 500, 20);
      }
      throw new IllegalArgumentException("unknown benchmark profile: " + name);
    }
  }
}

package com.datadog.openfeature.internal.flagevaluation;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.datadog.openfeature.internal.RuntimeServices;
import com.datadog.openfeature.internal.bridge.v1.HealthMetrics;
import java.util.Collections;
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
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Evaluating-thread benchmark for EVP {@code flagevaluation} recording: {@link
 * FlagEvaluationPipeline#record} into the caller-side aggregate, on one thread and on several
 * contending for the same buckets.
 *
 * <p><strong>Scope limitation:</strong> this benchmark starts from already-captured fields and a
 * ready-made flat attribute map. It therefore does NOT cover the OpenFeature hook's own inline
 * capture cost - in particular the bounded evaluation-context copy that
 * FlagEvalLoggingHook.finallyAfter performs under consent-on. That cost is measured separately by
 * FlagEvalHookHotPathBenchmark in the root package. Do not read these numbers as the total
 * per-evaluation cost paid by the caller.
 *
 * <p>Evaluations are recorded with observeFullEvaluationData=true so the aggregator actually hashes
 * and compares the context. Under the consent-off default it drops attrs and skips that work, which
 * makes every field-count profile measure the same scalar-only work.
 *
 * <p>The worker is never started, so nothing drains: after the first pass over the keys every
 * evaluation lands in an existing tally, the steady state of a flush window.
 *
 * <p>Run: {@code ./gradlew :dd-openfeature:jmh -Pjmh.includes=FlagEvaluationHotPathBenchmark}.
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
  private FlagEvaluationPipeline writer;

  /** Per-thread cursor: a shared counter would add its own cache-line contention. */
  @State(Scope.Thread)
  public static class Cursor {
    int next;
  }

  @Setup(Level.Iteration)
  public void setUp() {
    final Profile p = Profile.fromName(profile);

    attrs = new HashMap<>();
    for (int i = 0; i < p.numFields; i++) {
      attrs.put("field" + i, "value");
    }
    flagKeys = keys("bench-flag-", p.numFlags);
    targetingKeys = keys("bench-user-", p.numUsers);

    final Map<String, String> ddContext = new HashMap<>();
    ddContext.put("service", "bench-service");
    writer =
        new FlagEvaluationPipeline(
            Long.MAX_VALUE,
            NANOSECONDS,
            (route, json) -> {},
            ddContext,
            new RuntimeServices(HealthMetrics.NOOP));
  }

  /** Uncontended cost of recording one evaluation. */
  @Benchmark
  @Threads(1)
  public void record(final Cursor cursor) {
    recordNext(cursor);
  }

  /** Recording from several threads into the same buckets. */
  @Benchmark
  @Threads(8)
  public void recordContended(final Cursor cursor) {
    recordNext(cursor);
  }

  private void recordNext(final Cursor cursor) {
    final int i = cursor.next++;
    final int flag = Math.floorMod(i, flagKeys.length);
    writer.record(
        flagKeys[flag],
        VARIANTS[Math.floorMod(i, VARIANTS.length)],
        ALLOCATIONS[Math.floorMod(flag, ALLOCATIONS.length)],
        targetingKeys[Math.floorMod(i, targetingKeys.length)],
        null,
        1_700_000_000_000L + i,
        true,
        attrs,
        Collections.<String, Object>emptyMap());
  }

  private static final String[] VARIANTS = {"variant-0", "variant-1", "variant-2", "variant-3"};
  private static final String[] ALLOCATIONS = {"alloc-0", "alloc-1"};

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

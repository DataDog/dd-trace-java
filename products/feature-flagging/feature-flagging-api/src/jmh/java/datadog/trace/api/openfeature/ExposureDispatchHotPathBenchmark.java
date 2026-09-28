package datadog.trace.api.openfeature;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import datadog.trace.api.featureflag.FeatureFlaggingGateway;
import datadog.trace.api.featureflag.exposure.ExposureEvent;
import dev.openfeature.sdk.ImmutableMetadata;
import dev.openfeature.sdk.ImmutableStructure;
import dev.openfeature.sdk.MutableContext;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.Reason;
import dev.openfeature.sdk.Value;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
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
 * Measures the evaluation-thread cost of exposure admission and context capture.
 *
 * <p>The first path always captures an event. The duplicate path uses the same scalar identity for
 * every invocation. This separates the required first-event copy from avoidable duplicate copies.
 *
 * <p>Run: {@code ./gradlew :products:feature-flagging:feature-flagging-api:jmh
 * -PjmhIncludes=ExposureDispatchHotPathBenchmark -PjmhProf=gc}.
 */
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2, timeUnit = SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = SECONDS)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(NANOSECONDS)
@Fork(1)
public class ExposureDispatchHotPathBenchmark {

  @Param({"empty", "flat/100attrs", "nested/10structs_10fields", "wide/1000attrs"})
  public String shape;

  @Param({"first", "duplicate"})
  public String path;

  private MutableContext context;
  private ProviderEvaluation<String> evaluation;
  private BenchmarkExposureListener listener;

  @Setup(Level.Trial)
  public void setUp() {
    context = buildContext(shape);
    evaluation =
        ProviderEvaluation.<String>builder()
            .value("on-value")
            .variant("on")
            .reason(Reason.TARGETING_MATCH.name())
            .flagMetadata(ImmutableMetadata.builder().addString("allocationKey", "alloc-1").build())
            .build();
    listener = new BenchmarkExposureListener("first".equals(path));
    if ("duplicate".equals(path)) {
      listener.record("bench-flag", "bench-user", "on", "alloc-1");
    }
    FeatureFlaggingGateway.addExposureListener(listener);
  }

  @TearDown(Level.Trial)
  public void tearDown() {
    FeatureFlaggingGateway.removeExposureListener(listener);
  }

  @Benchmark
  public void dispatchExposure() {
    DDEvaluator.dispatchExposure("bench-flag", evaluation, context);
  }

  private static MutableContext buildContext(final String shape) {
    final MutableContext ctx = new MutableContext("bench-user");
    if ("empty".equals(shape)) {
      return ctx;
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
    if ("wide/1000attrs".equals(shape)) {
      return addFlat(ctx, 1_000);
    }
    throw new IllegalArgumentException("unknown benchmark shape: " + shape);
  }

  private static MutableContext addFlat(final MutableContext ctx, final int count) {
    for (int i = 0; i < count; i++) {
      ctx.add("field" + i, "value" + i);
    }
    return ctx;
  }

  private static final class BenchmarkExposureListener
      implements FeatureFlaggingGateway.ExposureListener {
    private final boolean alwaysCapture;
    private final ConcurrentMap<Identity, IdentityValue> identities = new ConcurrentHashMap<>();

    private BenchmarkExposureListener(final boolean alwaysCapture) {
      this.alwaysCapture = alwaysCapture;
    }

    @Override
    public boolean shouldCapture(
        final String flag, final String subject, final String variant, final String allocation) {
      final IdentityValue current = identities.get(new Identity(flag, subject));
      if (alwaysCapture) {
        return true;
      }
      return current == null || !current.matches(variant, allocation);
    }

    @Override
    public void accept(final ExposureEvent event) {
      record(event.flag.key, event.subject.id, event.variant.key, event.allocation.key);
    }

    private void record(
        final String flag, final String subject, final String variant, final String allocation) {
      identities.put(new Identity(flag, subject), new IdentityValue(variant, allocation));
    }
  }

  private static final class Identity {
    private final String flag;
    private final String subject;

    private Identity(final String flag, final String subject) {
      this.flag = flag;
      this.subject = subject;
    }

    @Override
    public boolean equals(final Object other) {
      if (this == other) {
        return true;
      }
      if (!(other instanceof Identity)) {
        return false;
      }
      final Identity identity = (Identity) other;
      return Objects.equals(flag, identity.flag) && Objects.equals(subject, identity.subject);
    }

    @Override
    public int hashCode() {
      return Objects.hash(flag, subject);
    }
  }

  private static final class IdentityValue {
    private final String variant;
    private final String allocation;

    private IdentityValue(final String variant, final String allocation) {
      this.variant = variant;
      this.allocation = allocation;
    }

    private boolean matches(final String otherVariant, final String otherAllocation) {
      return Objects.equals(variant, otherVariant) && Objects.equals(allocation, otherAllocation);
    }
  }
}

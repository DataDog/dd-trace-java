package datadog.trace.api;

import static java.util.concurrent.TimeUnit.SECONDS;

import datadog.trace.bootstrap.instrumentation.api.SpanPrototype;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Per-mechanism benchmark for {@link SpanPrototype}: the constant-tag application a span pays at
 * start. Compares the three phases of the mechanism, holding the resulting tag set identical:
 *
 * <ul>
 *   <li><b>oldPerSpanStamps</b> — a fresh {@code TagMap} filled by N individual {@code set(entry)}
 *       calls, as {@code BaseDecorator.afterStart} does today (once per span).
 *   <li><b>newBulkApply</b> — a fresh map + one {@code putAll} of the baked-once prototype (the
 *       afterStart-consolidation on-ramp).
 *   <li><b>newConstructionSeed</b> — the span's map is <em>born</em> as a {@code copy()} of the
 *       frozen prototype (clone-at-birth; what increment 2's construction wiring unlocks).
 * </ul>
 *
 * <p>Isolates the constant-application only (not span creation or the {@code afterStart} virtual
 * chain), so the delta is purely N-stamps vs. bulk-copy. Run with {@code -prof gc} — the
 * interesting axes are ops/s and B/op.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(SECONDS)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(3)
@Threads(8)
public class SpanPrototypeBenchmark {

  // The constant set a typical server span carries, as cached entries (the shared-Entry
  // hand-optimization the decorators use today).
  private static final TagMap.Entry COMPONENT = TagMap.Entry.create(Tags.COMPONENT, "netty");
  private static final TagMap.Entry KIND =
      TagMap.Entry.create(Tags.SPAN_KIND, Tags.SPAN_KIND_SERVER);
  private static final TagMap.Entry LANGUAGE =
      TagMap.Entry.create(DDTags.LANGUAGE_TAG_KEY, DDTags.LANGUAGE_TAG_VALUE);
  private static final TagMap.Entry ANALYTICS =
      TagMap.Entry.create(DDTags.ANALYTICS_SAMPLE_RATE, 1.0d);

  private SpanPrototype prototype;

  @Setup(Level.Trial)
  public void setUp() {
    // Baked once — the same constants, composed through the builder.
    prototype =
        SpanPrototype.builder()
            .initComponent("netty")
            .initKind(Tags.SPAN_KIND_SERVER)
            .initTag(DDTags.LANGUAGE_TAG_KEY, DDTags.LANGUAGE_TAG_VALUE)
            .initTag(ANALYTICS)
            .build();
  }

  @Benchmark
  public TagMap oldPerSpanStamps() {
    TagMap tags = TagMap.create();
    tags.set(COMPONENT);
    tags.set(KIND);
    tags.set(LANGUAGE);
    tags.set(ANALYTICS);
    return tags;
  }

  @Benchmark
  public TagMap newBulkApply() {
    TagMap tags = TagMap.create();
    tags.putAll(prototype.tags());
    return tags;
  }

  @Benchmark
  public TagMap newConstructionSeed() {
    return prototype.tags().copy();
  }
}

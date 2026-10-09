package datadog.trace.core;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

import datadog.trace.api.KnownTags;
import datadog.trace.api.TagMap;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Measures {@code DDSpan.setTag} on a live span, overwriting one tag per call, by how the tag is
 * named: by a constant id, by a known tag's name, and by a custom name. {@code tagMap_byId} is the
 * floor -- the same store on a bare {@link TagMap}, with no span, lock, or interceptor.
 *
 * <p>A constant id should cost close to the floor plus the span's lock: its interception test folds
 * to the id's INTERCEPTED bit, and an id set has no custom-tag path. The name variants pay a
 * registry lookup first.
 *
 * <p>Two controls separate those costs. {@code knownTag_byNonConstantId} reads the id from a
 * non-final field, which C2 never folds, so nothing about the id is known at compile time. {@code
 * tagMap_byId_synchronized} is the floor plus an uncontended lock, as the span takes one.
 */
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(NANOSECONDS)
@Fork(value = 3)
public class SetTagBenchmark {
  static final CoreTracer TRACER = CoreTracer.builder().build();

  private DDSpan span;
  private TagMap tagMap;
  private long nonConstantTagId;

  @Setup
  public void setup() {
    span = (DDSpan) TRACER.startSpan("benchmark", "set.tag");
    tagMap = TagMap.create();
    nonConstantTagId = KnownTags.HTTP_ROUTE_ID;
  }

  @TearDown
  public void tearDown() {
    span.finish();
  }

  @Benchmark
  public Object tagMap_byId() {
    tagMap.set(KnownTags.HTTP_ROUTE_ID, "/users/{id}");
    return tagMap;
  }

  @Benchmark
  public Object tagMap_byId_synchronized() {
    TagMap tagMap = this.tagMap;
    synchronized (tagMap) {
      tagMap.set(KnownTags.HTTP_ROUTE_ID, "/users/{id}");
    }
    return tagMap;
  }

  @Benchmark
  public Object knownTag_byNonConstantId() {
    return span.setTag(nonConstantTagId, "/users/{id}");
  }

  @Benchmark
  public Object knownTag_byId() {
    return span.setTag(KnownTags.HTTP_ROUTE_ID, "/users/{id}");
  }

  @Benchmark
  public Object interceptedTag_byId() {
    return span.setTag(KnownTags.SPAN_KIND_ID, "client");
  }

  @Benchmark
  public Object knownTag_byName() {
    return span.setTag(KnownTags.HTTP_ROUTE_NAME, "/users/{id}");
  }

  @Benchmark
  public Object customTag_byName() {
    return span.setTag("my.custom.tag", "/users/{id}");
  }

  @Benchmark
  public Object knownIntTag_byId() {
    return span.setTag(KnownTags.HTTP_RESEND_COUNT_ID, 2);
  }

  @Benchmark
  public Object knownIntTag_byName() {
    return span.setTag(KnownTags.HTTP_RESEND_COUNT_NAME, 2);
  }
}

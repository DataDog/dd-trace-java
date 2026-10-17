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
 * named: a constant id ({@code knownTag_byId}, {@code knownIntTag_byId}), a constant intercepted id
 * ({@code interceptedTag_byId}, {@code span.kind}), a known tag's name, and a custom name.
 *
 * <p>Controls: {@code tagMap_byId} is the same store on a bare {@link TagMap}, with no span, lock,
 * or interceptor; {@code tagMap_byId_synchronized} adds an uncontended lock, as the span takes one;
 * {@code knownTag_byNonConstantId} reads the id from a non-final field, which C2 never
 * constant-folds.
 *
 * <p>With a constant id, the interception test folds to the id's INTERCEPTED bit and there is no
 * custom-tag path, so comparing {@code knownTag_byId} with {@code tagMap_byId_synchronized}
 * isolates the span's own dispatch. The name variants add a registry lookup.
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

package datadog.trace.core;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

import datadog.trace.api.KnownTags;
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
 * Measures reading tags back from a live span, by how the tag is named: a known tag's name, a
 * custom name, a direction-scoped alias, and a miss. {@code getTag} takes the span's tag lock;
 * {@code unsafeGetTag} reads without it, as the tracer's internal readers do.
 */
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(NANOSECONDS)
@Fork(value = 3)
public class GetTagBenchmark {
  static final CoreTracer TRACER = CoreTracer.builder().build();

  private DDSpan span;

  @Setup
  public void setup() {
    span = (DDSpan) TRACER.startSpan("benchmark", "get.tag");
    span.setTag("span.kind", "client");
    span.setTag(KnownTags.HTTP_ROUTE_NAME, "/users/{id}");
    span.setTag(KnownTags.PEER_HOSTNAME_NAME, "db.example");
    span.setTag(KnownTags.HTTP_STATUS_CODE_NAME, 200);
    span.setTag("my.custom.tag", "custom");
    span.setTag("my.other.tag", 42);
  }

  @TearDown
  public void tearDown() {
    span.finish();
  }

  @Benchmark
  public Object knownTag_getTag() {
    return span.getTag(KnownTags.HTTP_ROUTE_NAME);
  }

  @Benchmark
  public Object customTag_getTag() {
    return span.getTag("my.custom.tag");
  }

  @Benchmark
  public Object aliasName_getTag() {
    return span.getTag("server.address");
  }

  @Benchmark
  public Object missingTag_getTag() {
    return span.getTag("not.there");
  }

  /** A known tag the span doesn't hold: a clear slot bit can answer without a probe. */
  @Benchmark
  public Object missingKnownTag_getTag() {
    return span.getTag(KnownTags.HTTP_METHOD_NAME);
  }

  @Benchmark
  public Object missingKnownTag_unsafeGetTag() {
    return span.unsafeGetTag(KnownTags.HTTP_METHOD_NAME);
  }

  @Benchmark
  public Object knownTag_unsafeGetTag() {
    return span.unsafeGetTag(KnownTags.HTTP_ROUTE_NAME);
  }

  @Benchmark
  public Object customTag_unsafeGetTag() {
    return span.unsafeGetTag("my.custom.tag");
  }
}

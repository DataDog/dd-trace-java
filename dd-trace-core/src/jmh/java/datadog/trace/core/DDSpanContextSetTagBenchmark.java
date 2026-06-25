package datadog.trace.core;

import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.common.writer.Writer;
import java.util.List;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Benchmark for {@code DDSpanContext.setTag} on a <b>primitive</b> value -- the path the caller
 * collapse touched. Before: {@code precheckIntercept} (an {@code isOptimized} call + a {@code
 * needsIntercept} resolve) and then, when intercepted, a second resolve inside {@code
 * interceptTag}. After: one {@code handlerId} resolve, then {@code handleIntercept(handlerId)}
 * directly.
 *
 * <p>Two paths: <b>intercepted</b> (e.g. {@code http.status_code} -- exercises the
 * double-&gt;single resolve), and <b>notIntercepted</b> (an app metric -- exercises the dropped
 * {@code isOptimized} call; stores the primitive without boxing). Per-thread span so the (mutating)
 * {@code setTag} has no cross-thread contention under {@code @Threads(8)}.
 *
 * <p>Run before (pre-collapse DDSpanContext) vs after by toggling DDSpanContext.java.
 *
 * <pre>
 *   ./gradlew :dd-trace-core:jmh   # add -prof gc
 * </pre>
 */
@Fork(2)
@Warmup(iterations = 2)
@Measurement(iterations = 5)
@Threads(8)
public class DDSpanContextSetTagBenchmark {

  private static final CoreTracer TRACER =
      CoreTracer.builder().writer(new NoopWriter()).strictTraceWrites(false).build();

  // A realistic mix set on one span: ~1/3 intercepted (resource/status/kind/error/service), ~2/3
  // ordinary app/integration tags. Cycling these through a single setTag call site keeps the
  // handlerId/handleIntercept dispatch polymorphic and interleaves the store -- closer to
  // production
  // than the single-arm benchmarks, and it should keep C2 out of the degenerate single-mode that
  // the
  // notIntercepted-only loop locks into. setTag(String, Object) is the manual-instrumentation path.
  private static final String[] MIXED_TAGS = {
    DDTags.RESOURCE_NAME,
    "http.useragent",
    "db.instance",
    Tags.HTTP_STATUS,
    "component",
    "thread.name",
    Tags.SPAN_KIND,
    "messaging.system",
    "network.peer.address",
    Tags.ERROR,
    "http.route",
    DDTags.SERVICE_NAME,
    "rpc.method",
    "app.queue.depth",
    "http.request.content_length"
  };
  private static final Object[] MIXED_VALUES = {
    "GET /api/users",
    "Mozilla/5.0",
    "orders",
    200,
    "netty",
    "worker-1",
    "server",
    "kafka",
    "10.0.0.1",
    Boolean.FALSE,
    "/api/users/{id}",
    "my-service",
    "GetUser",
    42,
    1024
  };

  @State(Scope.Thread)
  public static class SpanState {
    DDSpan span;
    int mixIdx;

    @Setup
    public void setup() {
      this.span = (DDSpan) TRACER.startSpan("benchmark", "op");
    }
  }

  @Benchmark
  public Object intercepted(SpanState s) {
    // DDSpan.setTag delegates to the refactored DDSpanContext.setTag(String, int)
    return s.span.setTag(Tags.HTTP_STATUS, 200);
  }

  @Benchmark
  public Object notIntercepted(SpanState s) {
    return s.span.setTag("app.queue.depth", 200);
  }

  /** Realistic mix of intercepted + non-intercepted tags cycled through one setTag call site. */
  @Benchmark
  public Object mixed(SpanState s) {
    int i = s.mixIdx;
    s.mixIdx = (i + 1 == MIXED_TAGS.length) ? 0 : i + 1;
    return s.span.setTag(MIXED_TAGS[i], MIXED_VALUES[i]);
  }

  static final class NoopWriter implements Writer {
    @Override
    public void write(List<DDSpan> trace) {}

    @Override
    public void start() {}

    @Override
    public boolean flush() {
      return true;
    }

    @Override
    public void close() {}

    @Override
    public void incrementDropCounts(int spanCount) {}
  }
}

package datadog.trace.core;

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
 * collapse touched. Before: {@code precheckIntercept} (an {@code isOptimized} call + a
 * {@code needsIntercept} resolve) and then, when intercepted, a second resolve inside
 * {@code interceptTag}. After: one {@code handlerId} resolve, then {@code handleIntercept(handlerId)}
 * directly.
 *
 * <p>Two paths: <b>intercepted</b> (e.g. {@code http.status_code} -- exercises the double-&gt;single
 * resolve), and <b>notIntercepted</b> (an app metric -- exercises the dropped {@code isOptimized}
 * call; stores the primitive without boxing). Per-thread span so the (mutating) {@code setTag} has no
 * cross-thread contention under {@code @Threads(8)}.
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

  @State(Scope.Thread)
  public static class SpanState {
    DDSpan span;

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

package stackstate.trace;

import io.opentracing.Span;
import io.opentracing.Tracer;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.State;
import stackstate.opentracing.STSTracer;
import stackstate.trace.common.writer.ListWriter;

public class STSTraceBenchmark {
  public static String SPAN_NAME = "span-benchmark";

  @State(org.openjdk.jmh.annotations.Scope.Thread)
  public static class TraceState {
    public ListWriter traceCollector = new ListWriter();
    public Tracer tracer = new STSTracer(traceCollector);
    // TODO: this will need to be fixed if we want backwards compatibility for older versions...
    public io.opentracing.Scope scope = tracer.buildSpan(SPAN_NAME).startActive(true);
  }

  @Benchmark
  public Object testBuildSpan(final TraceState state) {
    return state.tracer.buildSpan(SPAN_NAME);
  }

  @Benchmark
  public Object testBuildStartSpan(final TraceState state) {
    return state.tracer.buildSpan(SPAN_NAME).start();
  }

  @Benchmark
  public Object testFullSpan(final TraceState state) {
    final Span span = state.tracer.buildSpan(SPAN_NAME).start();
    span.finish();
    return span;
  }

  @Benchmark
  public Object testBuildStartSpanActive(final TraceState state) {
    return state.tracer.buildSpan(SPAN_NAME).startActive(true);
  }

  @Benchmark
  public Object testFullActiveSpan(final TraceState state) {
    final io.opentracing.Scope scope = state.tracer.buildSpan(SPAN_NAME).startActive(true);
    scope.close();
    return scope;
  }
}

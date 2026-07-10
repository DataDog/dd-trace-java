package datadog.trace.core;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

import datadog.trace.bootstrap.instrumentation.api.Tags;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Measures the cost of DDSpan.isOutbound(), which is called on every root span start and finish.
 */
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(NANOSECONDS)
@Fork(value = 1)
public class IsOutboundBenchmark {

  static final CoreTracer TRACER = CoreTracer.builder().build();

  private DDSpan clientSpan;
  private DDSpan serverSpan;
  private DDSpan unsetSpan;

  @Setup
  public void setup() {
    clientSpan = (DDSpan) TRACER.startSpan("benchmark", "client.op");
    clientSpan.setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_CLIENT);

    serverSpan = (DDSpan) TRACER.startSpan("benchmark", "server.op");
    serverSpan.setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_SERVER);

    unsetSpan = (DDSpan) TRACER.startSpan("benchmark", "unset.op");
  }

  @Benchmark
  public boolean isOutbound_client() {
    return clientSpan.isOutbound();
  }

  @Benchmark
  public boolean isOutbound_server() {
    return serverSpan.isOutbound();
  }

  @Benchmark
  public boolean isOutbound_unset() {
    return unsetSpan.isOutbound();
  }

  @Benchmark
  public Object getTag_spanKind_client() {
    return clientSpan.getTag(Tags.SPAN_KIND);
  }

  @Benchmark
  public Object getTag_spanKind_unset() {
    return unsetSpan.getTag(Tags.SPAN_KIND);
  }
}

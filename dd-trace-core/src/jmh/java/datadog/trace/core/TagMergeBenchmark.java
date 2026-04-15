package datadog.trace.core;

import static java.util.concurrent.TimeUnit.MICROSECONDS;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Benchmark measuring the tag-merge optimization in buildSpanContext.
 *
 * <p>Compares root span creation (which uses the pre-merged template of mergedTracerTags +
 * localRootSpanTags) versus child span creation (which uses mergedTracerTags as template).
 */
@State(Scope.Benchmark)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@BenchmarkMode(Mode.Throughput)
@Threads(8)
@OutputTimeUnit(MICROSECONDS)
@Fork(value = 1)
public class TagMergeBenchmark {
  static final CoreTracer TRACER = CoreTracer.builder().build();

  @Benchmark
  public AgentSpan rootSpan() {
    return TRACER.startSpan("foo", "bar");
  }

  @Benchmark
  public AgentSpan childSpan() {
    AgentSpan root = TRACER.startSpan("foo", "bar");
    return TRACER.startSpan("foo", "child", root.context());
  }
}

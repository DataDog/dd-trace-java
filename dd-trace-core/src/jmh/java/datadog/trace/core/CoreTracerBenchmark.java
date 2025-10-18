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
 * Benchmark of key operations of the CoreTracer
 *
 * <p>NOTE: This is a multi-threaded benchmark; single threaded benchmarks don't accurately reflect
 * some of the optimizations.
 *
 * <p>Use -t 1, if you'd like to do a single threaded run
 */
@State(Scope.Benchmark)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@BenchmarkMode(Mode.Throughput)
@Threads(8)
@OutputTimeUnit(MICROSECONDS)
@Fork(value = 1)
public class CoreTracerBenchmark {
  static final CoreTracer TRACER = CoreTracer.builder().build();

  @Benchmark
  public AgentSpan startSpan() {
    return TRACER.startSpan("foo", "bar");
  }

  @Benchmark
  public AgentSpan buildSpan() {
    return TRACER.buildSpan("foo", "bar").start();
  }

  @Benchmark
  public AgentSpan singleSpanBuilder() {
    return TRACER.singleSpanBuilder("foo", "bar").start();
  }
}

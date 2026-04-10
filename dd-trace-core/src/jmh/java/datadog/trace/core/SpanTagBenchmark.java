package datadog.trace.core;

import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
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

@BenchmarkMode(Mode.Throughput)
@Warmup(iterations = 5)
@Measurement(iterations = 5)
@Fork(2)
public class SpanTagBenchmark {

  static final CoreTracer TRACER = CoreTracer.builder().build();

  @State(Scope.Thread)
  public static class SpanPerThread {
    AgentSpan span;

    @Setup(Level.Invocation)
    public void setup() {
      span = TRACER.startSpan("benchmark", "tag-benchmark");
    }
  }

  @State(Scope.Benchmark)
  public static class SharedSpan {
    AgentSpan span;

    @Setup(Level.Invocation)
    public void setup() {
      span = TRACER.startSpan("benchmark", "tag-benchmark-shared");
    }
  }

  @Benchmark
  @Threads(1)
  @OutputTimeUnit(NANOSECONDS)
  public AgentSpan setStringTag_ownerThread(SpanPerThread state) {
    state.span.setTag("key", "value");
    return state.span;
  }

  @Benchmark
  @Threads(1)
  @OutputTimeUnit(NANOSECONDS)
  public AgentSpan setIntTag_ownerThread(SpanPerThread state) {
    state.span.setTag("key", 42);
    return state.span;
  }

  @Benchmark
  @Threads(1)
  @OutputTimeUnit(NANOSECONDS)
  public AgentSpan setTenTags_ownerThread(SpanPerThread state) {
    state.span.setTag("k0", "v0");
    state.span.setTag("k1", "v1");
    state.span.setTag("k2", "v2");
    state.span.setTag("k3", "v3");
    state.span.setTag("k4", "v4");
    state.span.setTag("k5", 5);
    state.span.setTag("k6", 6L);
    state.span.setTag("k7", 7.0);
    state.span.setTag("k8", true);
    state.span.setTag("k9", "v9");
    return state.span;
  }

  @Benchmark
  @Threads(8)
  @OutputTimeUnit(NANOSECONDS)
  public AgentSpan setStringTag_crossThread(SharedSpan state) {
    state.span.setTag("key", "value");
    return state.span;
  }

  @Benchmark
  @Threads(1)
  @OutputTimeUnit(MICROSECONDS)
  public AgentSpan fullLifecycle_tenTags(SpanPerThread state) {
    state.span.setTag("k0", "v0");
    state.span.setTag("k1", "v1");
    state.span.setTag("k2", "v2");
    state.span.setTag("k3", "v3");
    state.span.setTag("k4", "v4");
    state.span.setTag("k5", 5);
    state.span.setTag("k6", 6L);
    state.span.setTag("k7", 7.0);
    state.span.setTag("k8", true);
    state.span.setTag("k9", "v9");
    state.span.finish();
    return state.span;
  }
}

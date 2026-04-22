package datadog.trace.core;

import static java.util.concurrent.TimeUnit.MICROSECONDS;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

@State(Scope.Benchmark)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@BenchmarkMode(Mode.Throughput)
@Threads(8)
@OutputTimeUnit(MICROSECONDS)
@Fork(value = 1)
public class ScopeLifecycleBenchmark {
  static final CoreTracer TRACER = CoreTracer.builder().build();

  @State(Scope.Thread)
  public static class ThreadState {
    AgentSpan span;
    AgentSpan childSpan;
    AgentScope activeScope;

    @Setup(Level.Iteration)
    public void setup() {
      span = TRACER.startSpan("benchmark", "parent");
      childSpan = TRACER.startSpan("benchmark", "child");
      activeScope = TRACER.activateSpan(span);
    }

    @TearDown(Level.Iteration)
    public void tearDown() {
      activeScope.close();
      childSpan.finish();
      span.finish();
    }
  }

  @Benchmark
  public void activateAndClose(ThreadState state) {
    AgentScope scope = TRACER.activateSpan(state.span);
    scope.close();
  }

  @Benchmark
  public void activateSameSpan(ThreadState state) {
    AgentScope outer = TRACER.activateSpan(state.span);
    AgentScope inner = TRACER.activateSpan(state.span);
    inner.close();
    outer.close();
  }

  @Benchmark
  public void nestedActivateAndClose(ThreadState state) {
    AgentScope parentScope = TRACER.activateSpan(state.span);
    AgentScope childScope = TRACER.activateSpan(state.childSpan);
    childScope.close();
    parentScope.close();
  }

  @Benchmark
  public AgentSpan activeSpanLookup() {
    return TRACER.activeSpan();
  }
}

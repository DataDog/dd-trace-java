package datadog.trace.core;

import static java.util.concurrent.TimeUnit.MICROSECONDS;

import datadog.context.Context;
import datadog.trace.api.EndpointTracker;
import datadog.trace.api.Stateful;
import datadog.trace.api.profiling.ProfilingContextAttribute;
import datadog.trace.api.profiling.ProfilingScope;
import datadog.trace.api.profiling.Timer.TimerType;
import datadog.trace.api.profiling.Timing;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.ProfilerContext;
import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;
import datadog.trace.core.monitor.HealthMetrics;
import datadog.trace.core.scopemanager.ContinuableScopeManager;
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

/**
 * Per park/unpark cost of virtual-thread context propagation: the current full {@link
 * ContinuableScopeManager#swap(Context)} on every mount/unmount versus the proposed seed-once path
 * (nothing when profiling is off, a profiler rebind/unbind when it is on). ddprof's native {@code
 * setContext} is modelled by a stub {@link ProfilingContextIntegration} whose {@link Stateful}
 * writes four volatile longs and allocates nothing.
 *
 * <pre>
 * {@code ./gradlew :dd-trace-core:jmh -Pjmh.includes=VirtualThreadContextBenchmark -PtestJvm=21 -Pjmh.profilers=gc}
 * </pre>
 *
 * <p>Sample run (JDK 21.0.9, {@code @Threads(8)}; run-to-run variance is high, the alloc/op figures
 * are stable):
 *
 * <pre>{@code
 * Benchmark                                                   Mode  Cnt     Score     Error   Units
 * currentCycle_profilingOff                                  thrpt    5   463.841 ± 251.726  ops/us
 * currentCycle_profilingOff:gc.alloc.rate.norm               thrpt    5   176.002 ±   0.016    B/op
 * currentCycle_profilingOn                                   thrpt    5   272.253 ±  21.041  ops/us
 * currentCycle_profilingOn:gc.alloc.rate.norm                thrpt    5   288.003 ±   0.022    B/op
 * proposedSteady_profilingOff                                thrpt    5  5010.376 ± 354.716  ops/us
 * proposedSteady_profilingOff:gc.alloc.rate.norm             thrpt    5    ~0                   B/op
 * proposedRebindUnbind_profilingOn                           thrpt    5  1755.815 ± 473.954  ops/us
 * proposedRebindUnbind_profilingOn:gc.alloc.rate.norm        thrpt    5    ~0                   B/op
 * }</pre>
 */
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@BenchmarkMode(Mode.Throughput)
@Threads(8)
@OutputTimeUnit(MICROSECONDS)
@Fork(value = 1)
public class VirtualThreadContextBenchmark {

  static final CoreTracer TRACER = CoreTracer.builder().build();

  ContinuableScopeManager plainManager; // profiling off
  ContinuableScopeManager profiledManager; // profiling on

  @Setup
  public void setup() {
    plainManager = new ContinuableScopeManager(0, false);
    profiledManager =
        new ContinuableScopeManager(0, false, new StubProfiling(), HealthMetrics.NO_OP);
  }

  @State(Scope.Thread)
  public static class ThreadState {
    AgentSpan span;
    Context spanContext;
    Stateful profilerState;

    @Setup(Level.Trial)
    public void setup(VirtualThreadContextBenchmark bench) {
      span = TRACER.startSpan("benchmark", "vt");
      spanContext = span;
      profilerState = new StubProfiling().newScopeState((ProfilerContext) span.spanContext());
    }

    @TearDown(Level.Trial)
    public void tearDown() {
      span.finish();
    }
  }

  @Benchmark
  public void currentCycle_profilingOff(ThreadState t) {
    Context previous = plainManager.swap(t.spanContext);
    plainManager.swap(previous);
  }

  @Benchmark
  public void currentCycle_profilingOn(ThreadState t) {
    Context previous = profiledManager.swap(t.spanContext);
    profiledManager.swap(previous);
  }

  // current() is the faithful upper bound for the seed-once steady state (a scope-stack read).
  @Benchmark
  public Context proposedSteady_profilingOff(ThreadState t) {
    return plainManager.current();
  }

  @Benchmark
  public Context proposedRebindUnbind_profilingOn(ThreadState t) {
    Context active = profiledManager.current();
    t.profilerState.activate(t.span.spanContext());
    t.profilerState.close();
    return active;
  }

  static final class StubProfiling implements ProfilingContextIntegration {
    @Override
    public Stateful newScopeState(ProfilerContext profilerContext) {
      // Per-scope storage mirrors ddprof's per-OS-thread native slots, avoiding the cross-thread
      // cache contention a single shared instance would introduce.
      return new StubState();
    }

    @Override
    public String name() {
      return "stub";
    }

    @Override
    public ProfilingContextAttribute createContextAttribute(String attribute) {
      return ProfilingContextAttribute.NoOp.INSTANCE;
    }

    @Override
    public ProfilingScope newScope() {
      return ProfilingScope.NO_OP;
    }

    @Override
    public void onRootSpanFinished(AgentSpan rootSpan, EndpointTracker tracker) {}

    @Override
    public EndpointTracker onRootSpanStarted(AgentSpan rootSpan) {
      return EndpointTracker.NO_OP;
    }

    @Override
    public Timing start(TimerType type) {
      return Timing.NoOp.INSTANCE;
    }
  }

  static final class StubState implements Stateful {
    volatile long rootSpanId;
    volatile long spanId;
    volatile long traceHigh;
    volatile long traceLow;

    @Override
    public void activate(Object context) {
      if (context instanceof ProfilerContext) {
        ProfilerContext c = (ProfilerContext) context;
        rootSpanId = c.getRootSpanId();
        spanId = c.getSpanId();
        traceHigh = c.getTraceIdHigh();
        traceLow = c.getTraceIdLow();
      }
    }

    @Override
    public void close() {
      rootSpanId = 0;
      spanId = 0;
      traceHigh = 0;
      traceLow = 0;
    }
  }
}

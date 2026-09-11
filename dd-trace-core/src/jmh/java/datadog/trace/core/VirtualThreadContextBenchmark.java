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
 * Java allocation and control-flow cost of virtual-thread context propagation: the current full
 * {@link ContinuableScopeManager#swap(Context)} on every mount/unmount versus the proposed
 * seed-once path (nothing when profiling is off, a profiler rebind/unbind when it is on). ddprof's
 * native {@code setContext} is modelled by a shared stub {@link ProfilingContextIntegration} backed
 * by thread-local slots. The stub matches ddprof's integration lifetime, per-thread isolation, and
 * set/clear call pattern, but does not model native-call cost; profiling-on throughput is therefore
 * not an estimate of production performance.
 *
 * <pre>
 * {@code ./gradlew :dd-trace-core:jmh -Pjmh.includes=VirtualThreadContextBenchmark -PtestJvm=21 -Pjmh.profilers=gc}
 * </pre>
 *
 * <p>Use {@code -Pjmh.threads=1} as a control when comparing allocation per operation without
 * cross-thread effects.
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
  StubProfiling stubProfiling;

  @Setup
  public void setup() {
    plainManager = new ContinuableScopeManager(0, false);
    stubProfiling = new StubProfiling();
    profiledManager = new ContinuableScopeManager(0, false, stubProfiling, HealthMetrics.NO_OP);
  }

  @State(Scope.Thread)
  public static class ThreadState {
    AgentSpan span;
    Context spanContext;

    @Setup(Level.Trial)
    public void setup(VirtualThreadContextBenchmark bench) {
      span = TRACER.startSpan("benchmark", "vt");
      spanContext = span;
      bench.stubProfiling.initializeThread();
    }

    @TearDown(Level.Trial)
    public void tearDown(VirtualThreadContextBenchmark bench) {
      bench.stubProfiling.clearThread();
      span.finish();
    }
  }

  @Benchmark
  public void currentCycle_profilingOff(ThreadState t) {
    Context previous = plainManager.swap(t.spanContext);
    plainManager.swap(previous);
  }

  @Benchmark
  public long currentCycle_profilingOn_javaStub(ThreadState t) {
    Context previous = profiledManager.swap(t.spanContext);
    profiledManager.swap(previous);
    return stubProfiling.currentValue();
  }

  // current() is the faithful upper bound for the seed-once steady state (a scope-stack read).
  @Benchmark
  public Context proposedSteady_profilingOff(ThreadState t) {
    return plainManager.currentContext();
  }

  @Benchmark
  public long proposedRebindUnbind_profilingOn_javaStub(ThreadState t) {
    if (stubProfiling.isThreadContextBindingRequired()) {
      stubProfiling.setContext(t.spanContext);
      stubProfiling.setContext(Context.root());
    }
    return stubProfiling.currentValue();
  }

  static final class StubProfiling implements ProfilingContextIntegration {
    private final ThreadLocal<StubState> threadState = ThreadLocal.withInitial(StubState::new);

    private final Stateful contextManager =
        new Stateful() {
          @Override
          public void activate(Object context) {
            if (context instanceof ProfilerContext) {
              threadState.get().activate((ProfilerContext) context);
            }
          }

          @Override
          public void close() {
            threadState.get().close();
          }
        };

    @Override
    public Stateful newScopeState(ProfilerContext profilerContext) {
      return contextManager;
    }

    @Override
    public void setContext(Context context) {
      AgentSpan span = AgentSpan.fromContext(context);
      if (span != null) {
        contextManager.activate(span.spanContext());
      } else {
        contextManager.close();
      }
    }

    @Override
    public boolean isThreadContextBindingRequired() {
      return true;
    }

    void initializeThread() {
      threadState.get();
    }

    void clearThread() {
      threadState.remove();
    }

    long currentValue() {
      return threadState.get().currentValue();
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

  static final class StubState {
    long rootSpanId;
    long spanId;
    long traceHigh;
    long traceLow;
    long previousValue;

    void activate(ProfilerContext context) {
      rootSpanId = context.getRootSpanId();
      spanId = context.getSpanId();
      traceHigh = context.getTraceIdHigh();
      traceLow = context.getTraceIdLow();
    }

    void close() {
      previousValue = rootSpanId ^ spanId ^ traceHigh ^ traceLow;
      rootSpanId = 0;
      spanId = 0;
      traceHigh = 0;
      traceLow = 0;
    }

    long currentValue() {
      return previousValue ^ rootSpanId ^ spanId ^ traceHigh ^ traceLow;
    }
  }
}

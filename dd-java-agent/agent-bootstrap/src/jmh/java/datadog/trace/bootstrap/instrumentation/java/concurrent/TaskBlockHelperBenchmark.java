// Copyright 2026 Datadog, Inc.
package datadog.trace.bootstrap.instrumentation.java.concurrent;

import datadog.trace.api.EndpointTracker;
import datadog.trace.api.profiling.Timer;
import datadog.trace.api.profiling.Timing;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class TaskBlockHelperBenchmark {

  /** Measures the rejected-entry fast path. */
  @Benchmark
  public long beginRejected(BenchmarkState state) {
    return TaskBlockHelper.begin(state.rejected);
  }

  /** Measures the complete accepted Java lifecycle without a sleep or native crossing. */
  @Benchmark
  public void beginAndFinishAccepted(BenchmarkState state) {
    long token = TaskBlockHelper.begin(state.accepted);
    TaskBlockHelper.finish(state.accepted, token);
  }

  /** Measures the zero-token completion fast path. */
  @Benchmark
  public void finishZeroToken(BenchmarkState state) {
    TaskBlockHelper.finish(state.accepted, 0L);
  }

  /** Measures the zero-duration short-circuit that skips the tracer lookup entirely. */
  @Benchmark
  public void rejectedZeroDurationSleep(BenchmarkState state) throws InterruptedException {
    TaskBlockHelper.sleep(state.rejected, 0L);
  }

  @State(Scope.Thread)
  public static class BenchmarkState {
    final ProfilingContextIntegration rejected = ProfilingContextIntegration.NoOp.INSTANCE;
    final ProfilingContextIntegration accepted = new AcceptedIntegration();
  }

  private static final class AcceptedIntegration implements ProfilingContextIntegration {
    @Override
    public long beginTaskBlock() {
      return 17L;
    }

    @Override
    public boolean endTaskBlock(long token, long blocker, long unblockingSpanId) {
      return true;
    }

    @Override
    public String name() {
      return "benchmark";
    }

    @Override
    public void onRootSpanFinished(AgentSpan rootSpan, EndpointTracker tracker) {}

    @Override
    public EndpointTracker onRootSpanStarted(AgentSpan rootSpan) {
      return EndpointTracker.NO_OP;
    }

    @Override
    public Timing start(Timer.TimerType type) {
      return Timing.NoOp.INSTANCE;
    }
  }
}

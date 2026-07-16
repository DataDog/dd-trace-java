// Copyright 2026 Datadog, Inc.
package datadog.trace.bootstrap.instrumentation.java.concurrent;

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
  public TaskBlockHelper.State captureRejected(BenchmarkState state) {
    return TaskBlockHelper.captureForSleep(state.rejected);
  }

  /** Measures an accepted entry paired with synchronous completion. */
  @Benchmark
  public void captureAndFinishAccepted(BenchmarkState state) {
    TaskBlockHelper.finish(state.acceptedState);
  }

  /** Measures the null-state completion fast path. */
  @Benchmark
  public void finishNull() {
    TaskBlockHelper.finish(null);
  }

  @State(Scope.Thread)
  public static class BenchmarkState {
    final ProfilingContextIntegration rejected = ProfilingContextIntegration.NoOp.INSTANCE;
    final TaskBlockHelper.State acceptedState =
        new TaskBlockHelper.State(ProfilingContextIntegration.NoOp.INSTANCE, 17L);
  }
}

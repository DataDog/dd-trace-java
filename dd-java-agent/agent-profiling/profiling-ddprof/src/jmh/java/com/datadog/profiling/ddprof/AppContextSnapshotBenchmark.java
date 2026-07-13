package com.datadog.profiling.ddprof;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Verifies that the ScopeStack pool approach (AppContextSnapshot.copyFrom into a pre-allocated
 * slot) is allocation-free on the save/restore hot path.
 *
 * <p>The {@code deepStack} benchmark uses {@code stackDepth=16} to trigger a one-time resize
 * (default pool size is 8) and confirm that subsequent iterations at that depth are still
 * allocation-free once the pool has grown.
 *
 * <p>Run with: ./gradlew :dd-java-agent:agent-profiling:profiling-ddprof:jmh
 * -PjmhIncludes=AppContextSnapshotBenchmark -PjmhProf=gc
 *
 * <p>Expected: gc.alloc.rate.norm ≈ 0 B/op for save, restore, and deepStack (steady state).
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class AppContextSnapshotBenchmark {

  @Param({"2", "8"})
  int attrCount;

  /**
   * Stack depth for the deepStack benchmark — 16 forces one resize past the default 8-slot pool.
   */
  @Param({"8", "16"})
  int stackDepth;

  private DatadogProfiler.AppContextSnapshot source;
  private DatadogProfiler.AppContextSnapshot slot;
  private DatadogProfiler.ScopeStack stack;

  @Setup
  public void setup() {
    source = new DatadogProfiler.AppContextSnapshot(attrCount);
    for (int i = 0; i < attrCount; i++) {
      byte[] utf8 = ("value-" + i).getBytes(StandardCharsets.UTF_8);
      source.record(i, i + 1, utf8, "value-" + i);
    }
    slot = new DatadogProfiler.AppContextSnapshot(attrCount);
    stack = new DatadogProfiler.ScopeStack(attrCount);
  }

  /** ScopeStack save: copies current snapshot into a pre-allocated pool slot (zero alloc). */
  @Benchmark
  public void save() {
    slot.copyFrom(source);
  }

  /** ScopeStack restore: copies pool slot back into the live snapshot (zero alloc). */
  @Benchmark
  public void restore() {
    source.copyFrom(slot);
  }

  /**
   * Borrow {@code stackDepth} slots then release them all. At {@code stackDepth=16} the pool
   * resizes during warmup; steady-state measurement confirms zero allocation after growth.
   */
  @Benchmark
  public void deepStack() {
    for (int i = 0; i < stackDepth; i++) {
      stack.borrow().copyFrom(source);
    }
    for (int i = 0; i < stackDepth; i++) {
      stack.release();
    }
  }
}

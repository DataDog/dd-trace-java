package com.datadog.iast.taint;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.infra.Blackhole;

@Warmup(iterations = 2, time = 1000, timeUnit = MILLISECONDS)
@Measurement(iterations = 3, time = 1000, timeUnit = MILLISECONDS)
@Fork(3)
@OutputTimeUnit(NANOSECONDS)
@BenchmarkMode(Mode.AverageTime)
@State(Scope.Benchmark)
public class TaintedMapEmptyBenchmark {

  private static final int OP_COUNT = 1024;
  private TaintedMap map;
  private final Object anyObject = new Object();

  @Setup(Level.Iteration)
  public void setup(BenchmarkParams params) {
    final boolean baseline = params.getBenchmark().endsWith("baseline");
    map = baseline ? TaintedMap.NoOp.INSTANCE : new TaintedMap.TaintedMapImpl();
  }

  @Benchmark
  @OperationsPerInvocation(OP_COUNT)
  public void baseline(final Blackhole bh) {
    for (int i = 0; i < OP_COUNT; i++) {
      bh.consume(map.get(anyObject));
    }
  }

  @Benchmark
  @OperationsPerInvocation(OP_COUNT)
  public void getFromEmptyMap(final Blackhole bh) {
    for (int i = 0; i < OP_COUNT; i++) {
      bh.consume(map.get(anyObject));
    }
  }
}

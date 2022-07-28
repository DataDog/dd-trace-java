package com.datadog.profiling.context;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
public class TimeBucketsBenchmark {
  private TimeBuckets instance = null;

  @Setup(Level.Iteration)
  public void setup() throws Exception {
    instance = new TimeBuckets(10, 1, TimeUnit.MILLISECONDS, 4, 10_000_000);
  }

  @TearDown(Level.Iteration)
  public void teardown() throws Exception {
    instance = null;
  }

  @Benchmark
  @Threads(4)
  public void expiringAdds4(Blackhole bh) {
    TimeBuckets.Expiring rslt = instance.add(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()), () -> {});
    bh.consume(rslt);
  }

  @Benchmark
  @Threads(2)
  public void expiringAdds2(Blackhole bh) {
    TimeBuckets.Expiring rslt = instance.add(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()), () -> {});
    bh.consume(rslt);
  }

  @Benchmark
  @Threads(1)
  public void expiringAdds1(Blackhole bh) {
    TimeBuckets.Expiring rslt = instance.add(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()), () -> {});
    bh.consume(rslt);
  }

  @Benchmark
  @Threads(1)
  public void expiringAddsBase(Blackhole bh) {
    long rslt = System.nanoTime();
    if (rslt < 0) {
      throw new RuntimeException();
    }
    bh.consume(rslt);
  }
}

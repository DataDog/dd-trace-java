package com.datadog.profiling.context;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.Blackhole;

@State(Scope.Benchmark)
public class ExpirationTrackerBenchmark {
  private ExpirationTracker<String, ExpirableString> tracker = null;

  @Setup
  public void setup() {
    tracker = new ExpirationTracker<>(100_000, 1_000L, 100_000L);
  }

  @TearDown
  public void teardown() {
    if (tracker != null) {
      tracker.close();
    }
  }

  @Benchmark
  public void testTrack(Blackhole bh) throws InterruptedException {
    bh.consume(tracker.track(new ExpirableString("aaa")));
  }
}

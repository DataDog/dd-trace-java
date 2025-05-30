package com.datadog.iast.overhead;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import datadog.trace.api.Config;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

@Warmup(iterations = 1, time = 1000, timeUnit = MILLISECONDS)
@Measurement(iterations = 5, time = 1000, timeUnit = MILLISECONDS)
@OutputTimeUnit(MILLISECONDS)
@BenchmarkMode(Mode.Throughput)
@State(Scope.Benchmark)
public class OverheadControllerBenchmark {

  private OverheadController overheadController;

  @Setup(Level.Trial)
  public void setup() {
    System.setProperty("dd.iast.request-sampling", "100");
    System.setProperty("dd.iast.max-context-operations", "100000");
    overheadController = OverheadController.build(Config.get(), null);
  }

  @Benchmark
  public void acquireReleaseRequestNoSampling() {
    if (overheadController.acquireRequest()) {
      overheadController.releaseRequest();
    } else {
      throw new IllegalStateException();
    }
  }

  @Benchmark
  public void consumeQuota() {
    overheadController.consumeQuota(Operations.REPORT_VULNERABILITY, null, null);
  }
}

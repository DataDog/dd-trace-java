package datadog.trace.instrumentation.jmh.benchmarks;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Runs two measurement iterations so the primary result's statistics hold more than one data point
 * ({@code getN() > 1}) — the gate for emitting the distribution metrics (p50/p90/p95/p99/min/max/
 * sample_count). The single-iteration benchmarks cover the complementary path where they are
 * omitted.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 1, time = 1, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 2, time = 1, timeUnit = TimeUnit.MILLISECONDS)
@Fork(0)
public class DistributionBenchmark {
  @Benchmark
  public int measure() {
    return 42;
  }
}

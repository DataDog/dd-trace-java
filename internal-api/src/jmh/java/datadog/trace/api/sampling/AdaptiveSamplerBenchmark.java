package datadog.trace.api.sampling;

import static datadog.trace.api.sampling.AdaptiveSamplerBenchmark.ITERATION_TIME_MILLIS;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

@Warmup(iterations = 5, time = ITERATION_TIME_MILLIS, timeUnit = MILLISECONDS)
@Measurement(iterations = 5, time = ITERATION_TIME_MILLIS, timeUnit = MILLISECONDS)
@OutputTimeUnit(MILLISECONDS)
@BenchmarkMode(Mode.Throughput)
@State(Scope.Benchmark)
public class AdaptiveSamplerBenchmark {

  public static final int ITERATION_TIME_MILLIS = 1000;
  public static final int BUDGET_LOOKBACK = 16;

  @Param("5000")
  int samplesPerWindow;

  @Param("500")
  long durationWindowMillis;

  private AdaptiveSampler sampler;

  @Setup(Level.Iteration)
  public void setup() {
    int averageLookback = (int) (ITERATION_TIME_MILLIS / durationWindowMillis);
    sampler =
        new AdaptiveSampler(
            Duration.of(durationWindowMillis, ChronoUnit.MILLIS),
            samplesPerWindow,
            averageLookback,
            BUDGET_LOOKBACK,
            true);
  }

  @Threads(4)
  @Benchmark
  public boolean sample(SamplerCounters counters) {
    boolean sampled = sampler.sample();
    if (sampled) {
      ++counters.sampled;
    }
    ++counters.tests;
    return sampled;
  }
}

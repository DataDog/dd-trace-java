package datadog.trace.util.stacktrace;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import de.thetaphi.forbiddenapis.SuppressForbidden;
import external.util.stacktrace.RecursiveRunner;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
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
import org.openjdk.jmh.annotations.Warmup;

/**
 * Simple Benchmark to test that HotSpotStackWalker has better performance that
 * DefaultSpotStackWalker for JDK8 with hotspot
 */
@Warmup(iterations = 5, time = 1000, timeUnit = MILLISECONDS)
@Measurement(iterations = 5, time = 1000, timeUnit = MILLISECONDS)
@OutputTimeUnit(MILLISECONDS)
@BenchmarkMode(Mode.Throughput)
@State(Scope.Benchmark)
public class HotSpotStackWalkerBenchmark {

  private HotSpotStackWalker hotSpotStackWalker;

  private DefaultStackWalker defaultStackWalker;

  @Param({"1", "3", "10"})
  int limit;

  @Param({"10", "50", "100"})
  int deep;

  @Setup(Level.Trial)
  public void setup() {
    hotSpotStackWalker = new HotSpotStackWalker();
    defaultStackWalker = new DefaultStackWalker();
  }

  @Benchmark
  public void hotSpotStackWalkerWalk() {
    generateStack(hotSpotStackWalker);
  }

  @Benchmark
  public void defaultStackWalkerWalk() {
    generateStack(defaultStackWalker);
  }

  private void generateStack(final StackWalker stackWalker) {

    Runnable runnable =
        new Runnable() {
          @SuppressForbidden
          @Override
          public void run() {
            stackWalker.walk(this::toLimitedList).forEach(System.out::println);
          }

          private List<StackTraceElement> toLimitedList(final Stream<StackTraceElement> stack) {
            return stack.limit(limit).collect(Collectors.toList());
          }
        };

    RecursiveRunner runner = new RecursiveRunner(deep, runnable);
  }
}

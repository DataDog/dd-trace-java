package datadog.trace.util.stacktrace;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

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
 * Simple Benchmark to test that JDK9StackWalker has better performance that DefaultSpotStackWalker
 * for jdk9+
 */
@Warmup(iterations = 5, time = 1000, timeUnit = MILLISECONDS)
@Measurement(iterations = 5, time = 1000, timeUnit = MILLISECONDS)
@OutputTimeUnit(MILLISECONDS)
@BenchmarkMode(Mode.Throughput)
@State(Scope.Benchmark)
public class JDK9StackWalkerBenchmark {

  private JDK9StackWalker jdk9StackWalker;

  private DefaultStackWalker defaultStackWalker;

  @Param({"1", "3", "10"})
  int limit;

  @Param({"10", "50", "100"})
  int deep;

  @Setup(Level.Trial)
  public void setup() {
    jdk9StackWalker = new JDK9StackWalker();
    defaultStackWalker = new DefaultStackWalker();
  }

  @Benchmark
  public void JDK9StackWalkerWalk() {
    generateStack(jdk9StackWalker);
  }

  @Benchmark
  public void defaultStackWalkerWalk() {
    generateStack(defaultStackWalker);
  }

  private List<StackTraceElement> toLimitedList(final Stream<StackTraceElement> stack) {
    return stack.limit(limit).collect(Collectors.toList());
  }

  private void generateStack(final StackWalker stackWalker) {

    Runnable runnable =
        new Runnable() {
          @Override
          public void run() {
            stackWalker.walk(this::toLimitedList);
          }

          private List<StackTraceElement> toLimitedList(final Stream<StackTraceElement> stack) {
            return stack.limit(limit).collect(Collectors.toList());
          }
        };

    RecursiveRunner runner = new RecursiveRunner(deep, runnable);
    runner.run();
  }
}

package datadog.trace.api.telemetry;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

@Fork(2)
@Warmup(iterations = 2)
@Measurement(iterations = 5)
@Threads(8)
public class LogCollectorBenchmark {
  @State(Scope.Benchmark)
  public static class CollectorState {
    final LogCollector collector = new LogCollector(4);

    @Setup(Level.Trial)
    public void setup() {
      collector.addLogMessage("error", "ugh!", null);
    }
  }

  @Benchmark
  public void duplicateWithoutException(CollectorState state) {
    state.collector.addLogMessage("error", "ugh!", null);
  }

  static final Object NULL = null;

  @Benchmark
  public void nullPointerException(CollectorState state) {
    // Represents the fast throw case where the JVM switches to using
    // a single Exception instance to handle a hot throw location
    // of NullPointerException, ArrayIndexOutOfBoundsException, etc.
    // In this case, the stacktrace of the exception will not be available.
    try {
      NULL.hashCode();
    } catch (Throwable t) {
      state.collector.addLogMessage("error", "npe", t);
    }
  }

  @Benchmark
  public void unsupportedOperationException(CollectorState state) {
    // Represents the common case where stack trace is preserved
    // despite hot throw
    try {
      unsupportedOperation();
    } catch (Throwable t) {
      state.collector.addLogMessage("error", "unsupported", t);
    }
  }

  static void unsupportedOperation() {
    throw new UnsupportedOperationException();
  }
}

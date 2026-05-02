package datadog.trace.api.telemetry;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

@Fork(2)
@Warmup(iterations = 2)
@Measurement(iterations = 5)
@Threads(8)
public class LogCollectorBenchmark {
  @Benchmark
  public void noException_before() {
    LogCollector.get().addLogMessage("error", "ugh!", null);
  }

  static final Object NULL = null;

  @Benchmark
  public void nullPointerException() {
    // Represents the fast throw case where the JVM switches to using
    // a single Exception instance to handle a hot throw location
    // of NullPointerException, ArrayIndexOutOfBoundsException, etc.
    // In this case, the stacktrace of the exception will not be available.
    try {
      NULL.hashCode();
    } catch (Throwable t) {
      LogCollector.get().addLogMessage("error", "npe", t);
    }
  }

  @Benchmark
  public void unsupportedOperationException() {
    // Represents the common case where stack trace is preserved
    // despite hot throw
    try {
      unsupportedOperation();
    } catch (Throwable t) {
      LogCollector.get().addLogMessage("error", "unsupported", t);
    }
  }

  static void unsupportedOperation() {
    throw new UnsupportedOperationException();
  }
}

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
    LogCollector collector;

    @Setup(Level.Iteration)
    public void setup() {
      collector = new LogCollector(4);
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

  /**
   * Exercises the near-capacity path the other benchmarks skip: capacity is well below the number
   * of distinct keys in play, so once warmed up the table stays full and most calls miss {@code
   * find()}'s lock-free scan and fall through to {@code tryReserve} -- including its locked recheck
   * for a concurrent duplicate. {@link #duplicateWithoutException} and friends only ever hit the
   * lock-free fast path, so they don't touch that code at all.
   */
  @State(Scope.Benchmark)
  public static class ContendedCollectorState {
    static final int N_KEYS = 32;
    static final String[] MESSAGES = new String[N_KEYS];

    static {
      for (int i = 0; i < N_KEYS; i++) {
        MESSAGES[i] = "message-" + i;
      }
    }

    LogCollector collector;

    @Setup(Level.Iteration)
    public void setup() {
      // Capacity well below N_KEYS keeps the table full/near-full once warmed up.
      collector = new LogCollector(8);
    }
  }

  @State(Scope.Thread)
  public static class KeyCursorState {
    int cursor;

    int next() {
      int i = cursor;
      cursor = (i + 1) % ContendedCollectorState.N_KEYS;
      return i;
    }
  }

  @Benchmark
  public void variedKeysNearCapacity(ContendedCollectorState state, KeyCursorState cursor) {
    state.collector.addLogMessage("error", ContendedCollectorState.MESSAGES[cursor.next()], null);
  }
}

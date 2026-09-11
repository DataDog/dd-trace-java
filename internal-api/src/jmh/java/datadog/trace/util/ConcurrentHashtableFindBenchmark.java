package datadog.trace.util;

import static java.util.concurrent.TimeUnit.MICROSECONDS;

import javax.annotation.Nonnull;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Measures the lock-free hash-bucket scan used by {@link LogCollector#find}: a {@code for} loop
 * over {@link ConcurrentHashtable#hashIterable}, matching by key hash then a per-entry predicate.
 *
 * <p>Run with {@code -Pjmh.profilers=gc} to confirm the {@link Iterable}/{@link java.util.Iterator}
 * allocated per call (the anonymous instances returned by {@link
 * ConcurrentHashtable#hashIterable}/{@link ConcurrentHashtable#hashIterator}) are scalar-replaced
 * away by escape analysis rather than landing on the heap:
 *
 * <pre>{@code
 * ./gradlew :internal-api:jmh -Pjmh.includes=ConcurrentHashtableFindBenchmark -Pjmh.profilers=gc -Pjmh.forks=1
 * }</pre>
 */
@Fork(2)
@Warmup(iterations = 2)
@Measurement(iterations = 3)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(MICROSECONDS)
@Threads(8)
public class ConcurrentHashtableFindBenchmark {

  static final int N_KEYS = 64;
  static final int CAPACITY = 128;

  static final long[] KEY_HASHES = new long[N_KEYS];

  static {
    for (int i = 0; i < N_KEYS; ++i) {
      KEY_HASHES[i] = LongHashingUtils.hash("key-" + i);
    }
  }

  /** Mirrors {@code LogCollector.RawLogMessage}: a keyHash plus a payload compared on match. */
  static final class FindEntry extends ConcurrentHashtable.Entry<FindEntry> {
    final int payload;

    FindEntry(long keyHash, int payload) {
      super(keyHash);
      this.payload = payload;
    }

    @Override
    public boolean matches(@Nonnull FindEntry other) {
      return payload == other.payload;
    }
  }

  @State(Scope.Benchmark)
  public static class SharedState {
    ConcurrentHashtable.State<FindEntry> table;

    @Setup(Level.Iteration)
    public void setUp() {
      table = ConcurrentHashtable.createBounded(FindEntry.class, CAPACITY);
      for (int i = 0; i < N_KEYS; ++i) {
        ConcurrentHashtable.tryReserve(table, KEY_HASHES[i])
            .tryGetOrInsertOrNull(new FindEntry(KEY_HASHES[i], i));
      }
    }
  }

  @State(Scope.Thread)
  public static class ThreadState {
    int cursor;

    int next() {
      int i = cursor;
      cursor = (i + 1) & (N_KEYS - 1);
      return i;
    }
  }

  /** Same loop shape as {@code LogCollector.find}: scan candidates for a keyHash, match, return. */
  @Benchmark
  public FindEntry find(SharedState s, ThreadState t) {
    int i = t.next();
    for (FindEntry entry : ConcurrentHashtable.hashIterable(s.table, KEY_HASHES[i])) {
      if (entry.payload == i) {
        return entry;
      }
    }
    return null;
  }
}

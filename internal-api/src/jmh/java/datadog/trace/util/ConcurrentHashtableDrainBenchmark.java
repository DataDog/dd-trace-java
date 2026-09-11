package datadog.trace.util;

import static java.util.concurrent.TimeUnit.MICROSECONDS;

import javax.annotation.Nonnull;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Group;
import org.openjdk.jmh.annotations.GroupThreads;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Measures writer throughput while a background thread continuously {@link
 * ConcurrentHashtable#drain}s the same table -- the scenario behind the per-bucket lock acquisition
 * in {@code drain()} (one {@code lock()}/{@code unlock()} pair per bucket, instead of one held for
 * the whole sweep), added so writers can interleave between buckets rather than wait out an entire
 * drain.
 *
 * <p>The writer mirrors {@code LogCollector.addLogMessage}'s shape: a lock-free scan over a small,
 * rotating key set ({@link #N_KEYS} distinct log groups) that hits on the fast path once a key has
 * been inserted, falling back to {@link ConcurrentHashtable#tryReserve} (which holds the table lock
 * for the reservation's lifetime) only on a miss. Because {@code drainer} periodically empties the
 * table, writers keep taking the slow, lock-holding path throughout the run instead of settling
 * into steady-state lock-free hits -- unlike an unbounded-key writer, this keeps the table's
 * occupancy well under capacity so writers aren't dominated by the lock-free {@code isFull()}
 * fast-reject once full, and the throughput actually reflects contention for the table lock against
 * an in-progress drain.
 *
 * <p>One JMH {@code @Group} thread continuously drains; the rest continuously write. JMH reports
 * separate throughput for the {@code drainer} and {@code writer} roles under the {@code mixed}
 * group, so a regression in either role under contention is visible without needing to reconstruct
 * the old whole-sweep-lock strategy separately.
 *
 * <pre>{@code
 * ./gradlew :internal-api:jmh -Pjmh.includes=ConcurrentHashtableDrainBenchmark -Pjmh.forks=1
 * }</pre>
 */
@Fork(2)
@Warmup(iterations = 2)
@Measurement(iterations = 3)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(MICROSECONDS)
public class ConcurrentHashtableDrainBenchmark {

  static final int N_KEYS = 32;
  static final int CAPACITY = 64;

  static final long[] KEY_HASHES = new long[N_KEYS];

  static {
    for (int i = 0; i < N_KEYS; ++i) {
      KEY_HASHES[i] = LongHashingUtils.hash("key-" + i);
    }
  }

  static final class DrainEntry extends ConcurrentHashtable.Entry<DrainEntry> {
    DrainEntry(long keyHash) {
      super(keyHash);
    }

    @Override
    public boolean matches(@Nonnull DrainEntry other) {
      // hashIterator already filters candidates by keyHash equality before yielding them.
      return true;
    }
  }

  @State(Scope.Benchmark)
  public static class SharedState {
    ConcurrentHashtable.State<DrainEntry> table;

    @Setup(Level.Iteration)
    public void setUp() {
      table = ConcurrentHashtable.createBounded(DrainEntry.class, CAPACITY);
    }
  }

  @State(Scope.Thread)
  public static class WriterState {
    int cursor;

    int next() {
      int i = cursor;
      cursor = (i + 1) & (N_KEYS - 1);
      return i;
    }
  }

  @Benchmark
  @Group("mixed")
  @GroupThreads(1)
  public void drainer(SharedState s) {
    ConcurrentHashtable.drain(s.table, entry -> {});
  }

  @Benchmark
  @Group("mixed")
  @GroupThreads(3)
  public void writer(SharedState s, WriterState w) {
    long keyHash = KEY_HASHES[w.next()];
    for (DrainEntry entry : ConcurrentHashtable.hashIterable(s.table, keyHash)) {
      return; // lock-free hit, mirroring LogCollector.find()
    }
    try (ConcurrentHashtable.Reservation<DrainEntry> r = ConcurrentHashtable.tryReserve(s.table)) {
      if (r.isReserved()) {
        r.tryGetOrInsertOrNull(new DrainEntry(keyHash));
      }
    }
  }
}

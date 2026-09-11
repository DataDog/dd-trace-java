package datadog.trace.util;

import static java.util.concurrent.TimeUnit.MICROSECONDS;

import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
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
 * Compares writer throughput against a continuously draining table under two locking schemes:
 *
 * <ul>
 *   <li>{@code perBucketLock} -- {@link ConcurrentHashtable#drain}'s current strategy: the table
 *       lock is acquired and released once per bucket, so a writer can slip in between buckets
 *       instead of waiting out the whole sweep.
 *   <li>{@code wholeSweepLock} -- the strategy {@code drain()} used before this PR: the table lock
 *       is acquired once and held for the entire sweep. Reimplemented locally ({@link
 *       #drainWholeSweepLock}) using only {@code ConcurrentHashtable}'s public building blocks, so
 *       this file doesn't depend on checking out an older commit to get the comparison.
 * </ul>
 *
 * <p>Both variants release capacity per-entry (one {@link
 * ConcurrentHashtable.SizeManager#decrement()} per drained entry, mirroring current production
 * behavior) so the only variable under test is lock granularity, not the capacity-release change
 * that landed alongside it.
 *
 * <p>The writer mirrors {@code LogCollector.addLogMessage}'s shape: a lock-free scan over a small,
 * rotating key set ({@link #N_KEYS} distinct log groups) that hits on the fast path once a key has
 * been inserted, falling back to {@link ConcurrentHashtable#tryReserve} (which holds the table lock
 * for the reservation's lifetime) only on a miss. Because {@code drainer} periodically empties the
 * table, writers keep taking the slow, lock-holding path throughout the run instead of settling
 * into steady-state lock-free hits -- this keeps occupancy well under capacity so writers aren't
 * dominated by the lock-free {@code isFull()} fast-reject once full, and the throughput actually
 * reflects contention for the table lock against an in-progress drain.
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

  /**
   * The pre-PR strategy: one lock acquisition held for the entire bucket-array sweep, still
   * releasing capacity per-entry (only lock granularity differs from the current {@code drain()}).
   */
  private static <TEntry extends ConcurrentHashtable.Entry<TEntry>> void drainWholeSweepLock(
      @Nonnull ConcurrentHashtable.State<TEntry> state,
      @Nonnull Consumer<? super TEntry> drainedEntryConsumer) {
    ReentrantLock lock = ConcurrentHashtable.getTableWriteLock(state);
    AtomicReferenceArray<TEntry> buckets = state.buckets;
    lock.lock();
    try {
      for (int i = 0; i < buckets.length(); i++) {
        TEntry head = buckets.get(i);
        if (head == null) {
          continue;
        }
        buckets.set(i, null);
        for (TEntry e = head; e != null; e = e.next()) {
          state.sizeManager.decrement();
          drainedEntryConsumer.accept(e);
        }
      }
      state.sizeManager.release(0); // full sweep: reset the scan position
    } finally {
      lock.unlock();
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

  private static void write(ConcurrentHashtable.State<DrainEntry> table, WriterState w) {
    long keyHash = KEY_HASHES[w.next()];
    for (DrainEntry entry : ConcurrentHashtable.hashIterable(table, keyHash)) {
      return; // lock-free hit, mirroring LogCollector.find()
    }
    try (ConcurrentHashtable.Reservation<DrainEntry> r = ConcurrentHashtable.tryReserve(table)) {
      if (r.isReserved()) {
        r.tryGetOrInsertOrNull(new DrainEntry(keyHash));
      }
    }
  }

  @Benchmark
  @Group("perBucketLock")
  @GroupThreads(1)
  public void perBucketLockDrainer(SharedState s) {
    ConcurrentHashtable.drain(s.table, entry -> {});
  }

  @Benchmark
  @Group("perBucketLock")
  @GroupThreads(3)
  public void perBucketLockWriter(SharedState s, WriterState w) {
    write(s.table, w);
  }

  @Benchmark
  @Group("wholeSweepLock")
  @GroupThreads(1)
  public void wholeSweepLockDrainer(SharedState s) {
    drainWholeSweepLock(s.table, entry -> {});
  }

  @Benchmark
  @Group("wholeSweepLock")
  @GroupThreads(3)
  public void wholeSweepLockWriter(SharedState s, WriterState w) {
    write(s.table, w);
  }
}

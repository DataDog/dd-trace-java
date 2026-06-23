package datadog.trace.util;

import static java.util.concurrent.TimeUnit.MICROSECONDS;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.LongAdder;
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
 * Benchmarks the "find and increment" pattern: look up an entry by key, then atomically increment
 * its counter. Models per-class or per-method hit counters in the tracer.
 *
 * <p>The key insight is that {@link ConcurrentHashtable.D1} allows the counter to be embedded
 * directly in the entry as a {@code volatile long} updated via {@link AtomicLongFieldUpdater},
 * avoiding the extra object allocation that {@link ConcurrentHashMap} requires when pairing each
 * key with an {@link AtomicLong} or {@link LongAdder}.
 *
 * <p>Strategies compared:
 *
 * <ul>
 *   <li>{@link ConcurrentHashtable.D1} + {@link AtomicLongFieldUpdater} — lock-free lookup, inline
 *       counter; one object per entry total.
 *   <li>{@link ConcurrentHashMap} + {@link AtomicLong} — striped-lock lookup, one extra object per
 *       entry for the counter.
 *   <li>{@link ConcurrentHashMap} + {@link LongAdder} — striped-lock lookup, one extra object per
 *       entry; {@link LongAdder} reduces CAS contention under high thread counts at the cost of
 *       slightly higher memory and a more expensive {@code sum()}.
 * </ul>
 *
 * <p><b>Key identity.</b> Lookups reuse the same interned {@code KEYS} instances used to populate
 * the table, so they hit the {@code ==} identity fast path rather than {@code equals()}. This is
 * deliberate and realistic for the tracer, whose keys are typically interned string literals
 * (tag-name constants); it is <i>not</i> an oversight.
 *
 * <p>Java 17 results ({@code @Fork(2)}, {@code @Threads(8)}, 64 pre-populated keys):
 *
 * <pre>{@code
 * Benchmark                          Score   Units
 * increment_longAdder                   79   ops/us  (fastest)
 * increment_atomicLong                  71   ops/us
 * increment_concurrentHashtable         69   ops/us
 * }</pre>
 *
 * <p>Key findings:
 *
 * <ul>
 *   <li>All three strategies are within 15% of each other under 8 threads — the {@code
 *       ConcurrentHashMap} lookup, not the counter increment, dominates the cost in all baselines.
 *   <li>{@code LongAdder} is marginally faster (79 vs 71 ops/us) because it shards the counter
 *       across cells to reduce CAS contention; the advantage grows with thread count.
 *   <li>{@code ConcurrentHashtable} matches {@code AtomicLong} throughput (69 vs 71 ops/us) while
 *       embedding the counter directly in the entry — one object instead of two, with no throughput
 *       penalty.
 * </ul>
 */
@Fork(2)
@Warmup(iterations = 2)
@Measurement(iterations = 3)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(MICROSECONDS)
@Threads(8)
public class ThreadSafeMapCounterBenchmark {

  static final int N_KEYS = 64;
  static final int CAPACITY = 128;

  static final String[] KEYS = new String[N_KEYS];

  static {
    for (int i = 0; i < N_KEYS; ++i) {
      KEYS[i] = "key-" + i;
    }
  }

  static final class CounterEntry extends ConcurrentHashtable.D1.Entry<String> {
    private static final AtomicLongFieldUpdater<CounterEntry> COUNT =
        AtomicLongFieldUpdater.newUpdater(CounterEntry.class, "count");

    volatile long count;

    CounterEntry(String key) {
      super(key);
    }

    long increment() {
      return COUNT.incrementAndGet(this);
    }
  }

  /**
   * Shared state ({@link Scope#Benchmark}): one instance of each map across all threads, modelling
   * a shared instrumentation counter table.
   */
  @State(Scope.Benchmark)
  public static class SharedState {
    ConcurrentHashtable.D1<String, CounterEntry> table;
    ConcurrentHashMap<String, AtomicLong> atomicLongMap;
    ConcurrentHashMap<String, LongAdder> longAdderMap;

    @Setup(Level.Iteration)
    public void setUp() {
      table = new ConcurrentHashtable.D1<>(CAPACITY);
      atomicLongMap = new ConcurrentHashMap<>(CAPACITY);
      longAdderMap = new ConcurrentHashMap<>(CAPACITY);
      for (int i = 0; i < N_KEYS; ++i) {
        table.getOrCreate(KEYS[i], CounterEntry::new);
        atomicLongMap.put(KEYS[i], new AtomicLong());
        longAdderMap.put(KEYS[i], new LongAdder());
      }
    }
  }

  /** Per-thread cursor so each thread cycles through keys independently. */
  @State(Scope.Thread)
  public static class ThreadState {
    int cursor;

    int next() {
      int i = cursor;
      cursor = (i + 1) & (N_KEYS - 1);
      return i;
    }
  }

  @Benchmark
  public long increment_concurrentHashtable(SharedState s, ThreadState t) {
    return s.table.get(KEYS[t.next()]).increment();
  }

  @Benchmark
  public long increment_atomicLong(SharedState s, ThreadState t) {
    return s.atomicLongMap.get(KEYS[t.next()]).incrementAndGet();
  }

  @Benchmark
  public void increment_longAdder(SharedState s, ThreadState t) {
    s.longAdderMap.get(KEYS[t.next()]).increment();
  }
}

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
import org.openjdk.jmh.infra.Blackhole;

/**
 * Measures lookup followed by an atomic counter increment in a shared, pre-populated table. Models
 * per-class or per-method hit counters in the tracer.
 *
 * <p>The {@link ConcurrentHashtable.D1} case embeds a {@code volatile long} in each entry. {@link
 * AtomicLongFieldUpdater} updates that field atomically without allocating an {@link AtomicLong}
 * per key. The map baselines store a separate {@link AtomicLong} or {@link LongAdder}; {@code
 * LongAdder} spreads contention across internal cells at the cost of more memory and a more
 * expensive read.
 *
 * <p>Lookups reuse the key instances installed during setup. {@code Objects.equals} therefore
 * returns on its identity check without dispatching to {@code equals}, so this measures the
 * interned-key pattern used by the tracer rather than distinct-but-equal keys.
 *
 * <p>Java 17 results ({@code @Fork(2)}, {@code @Threads(8)}, 64 pre-populated keys):
 *
 * <pre>{@code
 * Benchmark                          Score   Units
 * increment_longAdder                   79   ops/us
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
 *
 * <p>Rerun with {@link BenchmarkUtils#warmUpHashDispatch} wired into {@code SharedState.setUp()},
 * same JDK 17 and machine as the table above. Same-JDK removes one variable, but separate JMH
 * invocations aren't a controlled A/B -- forks of a single benchmark method run back-to-back, while
 * the two tables here come from separate {@code ./gradlew jmh} invocations, so a systematic
 * difference between them (thermal state, background load, where the JIT happened to land) isn't
 * distinguishable from a pollution effect. Pollution itself is best-effort -- it raises the odds a
 * shared call site is megamorphic going into measurement, not a guarantee -- so treat this rerun as
 * illustrative, not as an isolated measurement of the pollution mechanism's effect:
 *
 * <pre>{@code
 * Benchmark                          Score   Units
 * increment_longAdder                  205   ops/us
 * increment_atomicLong                  72   ops/us
 * increment_concurrentHashtable         68   ops/us
 * }</pre>
 *
 * <p>{@code ConcurrentHashtable} and {@code AtomicLong} are still within 6% of each other (68 vs 72
 * ops/us), unchanged from above. {@code LongAdder}'s score jumped to 205 ops/us, but its error bar
 * ({@code ±429}) is more than double its own mean -- unusable at this fork count, and not evidence
 * of a real pollution effect. It equally cannot confirm the earlier within-15% comparison: that
 * finding rests on the first table's own data, and this run neither supports nor refutes it.
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

    // Front-load pollution once per trial, entirely before JMH's warmup starts: JMH
    // injects the Blackhole straight into this setup method, so no per-benchmark
    // scratch state is needed.
    @Setup(Level.Trial)
    public void warmUpPollution(Blackhole bh) {
      BenchmarkUtils.warmUpHashDispatch(bh);
    }

    @Setup(Level.Iteration)
    public void setUp() {
      table = ConcurrentHashtable.D1.createBounded(CounterEntry.class, CAPACITY);
      atomicLongMap = new ConcurrentHashMap<>(CAPACITY);
      longAdderMap = new ConcurrentHashMap<>(CAPACITY);
      for (int i = 0; i < N_KEYS; ++i) {
        table.tryGetOrCreateOrNull(KEYS[i], CounterEntry::new);
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

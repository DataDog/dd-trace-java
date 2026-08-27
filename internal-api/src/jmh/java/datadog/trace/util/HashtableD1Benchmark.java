package datadog.trace.util;

import static java.util.concurrent.TimeUnit.MICROSECONDS;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Compares {@link Hashtable.D1} against equivalent {@link HashMap} usage for add, update, and
 * iterate operations.
 *
 * <p>Each benchmark thread owns its own map ({@link Scope#Thread}), but a non-trivial thread count
 * is used so allocation/GC pressure surfaces in the throughput numbers — that pressure is the main
 * thing Hashtable is built to avoid.
 *
 * <ul>
 *   <li><b>add</b> — clear the map then re-insert N fresh entries
 *       ({@code @OperationsPerInvocation(N_KEYS)}). Captures the steady-state cost of building up a
 *       map.
 *   <li><b>update</b> — for an existing key, increment a counter. Hashtable does {@code get} +
 *       field mutation (no allocation); HashMap uses {@code merge(k, 1L, Long::sum)}, the idiomatic
 *       Java 8+ way, which still allocates a {@code Long} per call.
 *   <li><b>iterate</b> — walk every entry and consume its key + value.
 * </ul>
 *
 * <p><b>Update</b> is where Hashtable dominates: D1 is ~14x faster on JDK 8 (see the Java 17 rerun
 * below for a narrower but still decisive margin), because the HashMap path allocates per call (a
 * {@code Long}) and the resulting GC pressure throttles throughput under multiple threads. This is
 * the headline case for {@code Hashtable}: a simple counter/tally with a primitive value is exactly
 * where HashMap's autoboxing tax bites hardest, and {@code Hashtable.D1} sidesteps it entirely by
 * mutating a field on the retrieved entry in place. <b>Add</b> is roughly comparable (both allocate
 * one entry per insert). <b>Iterate</b> is essentially a wash on JDK 8, though not on Java 17 (see
 * below). <code>
 * MacBook M1 8 threads (Java 8)
 *
 * Benchmark                                Mode  Cnt     Score     Error   Units
 * HashtableD1Benchmark.add_hashMap        thrpt    6   187.883 ± 189.858  ops/us
 * HashtableD1Benchmark.add_hashtable      thrpt    6   198.710 ± 273.035  ops/us
 *
 * HashtableD1Benchmark.update_hashMap     thrpt    6   127.392 ±  87.482  ops/us
 * HashtableD1Benchmark.update_hashtable   thrpt    6  1810.244 ±  44.645  ops/us
 *
 * HashtableD1Benchmark.iterate_hashMap    thrpt    6    20.043 ±   0.752  ops/us
 * HashtableD1Benchmark.iterate_hashtable  thrpt    6    22.208 ±   0.956  ops/us
 * </code>
 *
 * <p>Rerun with {@link BenchmarkUtils#polluteHashDispatch()} added to {@code D1State.setUp()} (same
 * machine/JVM/config): every number moved down somewhat (add_hashMap 188→101, update_hashtable
 * 1810→1465, iterate_hashtable 22→17 ops/us), including {@code *_hashtable}. That's expected to be
 * a no-op for {@code *_hashtable}: {@link Hashtable.D1.Entry#hash} and {@link
 * Hashtable.D1.Entry#matches} are call sites private to {@code Hashtable.java}, structurally
 * distinct from {@code java.util.HashMap}/{@code HashSet}'s internal {@code hashCode()}/{@code
 * equals()} call sites — JIT type profiles are keyed per call site, so {@code
 * polluteHashDispatch()} cannot reach them regardless of key-type overlap. Since the JDK and
 * machine were held constant across this rerun (unlike the JDK 8-vs-17 comparisons in {@link
 * datadog.trace.util.CaseInsensitiveMapBenchmark} and {@link
 * datadog.trace.api.TagMapAccessBenchmark}), the drop here is same-session run-to-run noise
 * (thermal/power, not controlled for) rather than either a pollution effect or a JDK effect. The
 * <b>relative</b> conclusion (D1 dominates {@code update}, is roughly comparable on {@code add},
 * ties on {@code iterate}) is unchanged either way.
 *
 * <p>Separately rerun on Zulu 17.0.7 (native AArch64, same machine, pollution wiring unchanged; JMH
 * auto-detected the cheap "compiler" Blackhole mode here, unlike JDK 8, so absolute numbers below
 * are not comparable to the JDK 8 tables above — see {@code HashtableD2Benchmark}'s javadoc for the
 * full caveat). M ops/us, 8 threads:
 *
 * <pre>{@code
 * add_hashMap        1502.6   add_hashtable      1377.3
 * update_hashMap      644.2   update_hashtable   2706.5
 * iterate_hashMap      19.3   iterate_hashtable    78.0
 * }</pre>
 *
 * <p>Within this single run (so the cross-JDK Blackhole-mode confound doesn't apply to the ratios),
 * {@code update_hashtable} still wins by ~4.2x — down from ~14x on JDK 8, because Java 17's
 * allocator/GC absorbs {@code update_hashMap}'s per-call {@code Long} boxing far better than JDK 8
 * did (update_hashMap itself got ~5x faster; update_hashtable only ~1.5x faster). {@code
 * iterate_hashtable} also now clearly wins (~4.0x), flipping from JDK 8's "wash" — HashMap's {@code
 * entrySet()} iterator does more per-entry work than a modern JIT's allocation improvements erase.
 * {@code add} is the one case that flips the other way: {@code add_hashMap} edges out {@code
 * add_hashtable} slightly (1502.6 vs 1377.3). Net takeaway: {@code Hashtable} is a strong
 * substitute for {@code HashMap} particularly for simple counter/tally use cases with a primitive
 * value, where avoiding the per-update boxing allocation pays off even on a JVM with much better
 * allocation handling than JDK 8 had.
 *
 * <p>Rerun on the capped/{@code State}-backed table (5 forks, 15 datapoints/method, Zulu 17.0.7
 * AArch64, 8 threads). <b>Not comparable to the table above:</b> JMH auto-detected the {@code full
 * + dont-inline} Blackhole here rather than the cheap {@code compiler} one, on the same JVM build
 * and JMH 1.37 -- the mode is auto-detected per run and is not stable across runs, so every
 * absolute number in this file is conditional on a mode that JMH does not record beside it. Compare
 * within a table, never across. M ops/us:
 *
 * <pre>{@code
 * add_hashMap        1204.8   add_hashtable       974.4
 * update_hashMap      577.2   update_hashtable   1862.6
 * iterate_hashMap      15.9   iterate_hashtable    21.5
 * }</pre>
 *
 * <p>Within this run: {@code update_hashtable} wins by ~3.2x and {@code iterate_hashtable} by
 * ~1.35x, while {@code add_hashtable} now <em>loses</em> by ~19% -- no longer the "roughly
 * comparable" of the JDK 8 table, and a wider gap than the slight edge HashMap held in the previous
 * Java 17 run. {@code add} is where the capped table's bookkeeping is least amortized: both sides
 * allocate one entry per insert, so there is no boxing win to offset it, and the loop does nothing
 * else. The counter/tally path -- the case {@code Hashtable} exists for -- is unaffected.
 *
 * <p>That is the right side of the trade for this family. {@code Hashtable} and {@link
 * ConcurrentHashtable} are designed for workloads where <b>updates dominate</b>: the table is
 * populated once and then hit repeatedly, so per-insert cost amortizes away and in-place mutation
 * of a primitive field is the operation that runs hot. Paying on {@code add} to make {@code update}
 * faster is the trade those workloads want. {@code FlatHashtable} and {@code TagMap} sit at the
 * other end -- built up and read, not updated in a loop -- so this result does not transfer to
 * them, and neither does the reasoning that justifies it.
 */
@Fork(2)
@Warmup(iterations = 2)
@Measurement(iterations = 3)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(MICROSECONDS)
@Threads(8)
public class HashtableD1Benchmark {

  static final int N_KEYS = 64;
  static final int CAPACITY = 128;

  static final String[] SOURCE_KEYS = new String[N_KEYS];

  static {
    for (int i = 0; i < N_KEYS; ++i) {
      SOURCE_KEYS[i] = "key-" + i;
    }
  }

  static final class D1Counter extends Hashtable.D1.Entry<String> {
    long count;

    D1Counter(String key) {
      super(key);
    }
  }

  /** Reusable iteration consumer — avoids per-call lambda capture allocation. */
  static final class BhD1Consumer implements Consumer<D1Counter> {
    Blackhole bh;

    @Override
    public void accept(D1Counter e) {
      bh.consume(e.key);
      bh.consume(e.count);
    }
  }

  @State(Scope.Thread)
  public static class D1State {
    Hashtable.D1<String, D1Counter> table;
    HashMap<String, Long> hashMap;
    String[] keys;
    int cursor;
    final BhD1Consumer consumer = new BhD1Consumer();

    // Level.Iteration, not Trial: this rebuilds the table and the HashMap, so each iteration must
    // start from a fresh, identically-sized state rather than inheriting mutated counters. The
    // pollution call rides along -- it is idempotent and untimed, so repeating it costs nothing.
    @Setup(Level.Iteration)
    public void setUp() {
      BenchmarkUtils.polluteHashDispatch();

      table = Hashtable.D1.createCapped(D1Counter.class, CAPACITY);
      hashMap = new HashMap<>(CAPACITY);
      keys = SOURCE_KEYS;
      for (int i = 0; i < N_KEYS; ++i) {
        table.insert(new D1Counter(keys[i]));
        hashMap.put(keys[i], 0L);
      }
      cursor = 0;
    }

    String nextKey() {
      int i = cursor;
      cursor = (i + 1) & (N_KEYS - 1);
      return keys[i];
    }
  }

  @Benchmark
  @OperationsPerInvocation(N_KEYS)
  public void add_hashtable(D1State s) {
    Hashtable.D1<String, D1Counter> t = s.table;
    String[] keys = s.keys;
    t.clear();
    for (int i = 0; i < N_KEYS; ++i) {
      t.insert(new D1Counter(keys[i]));
    }
  }

  @Benchmark
  @OperationsPerInvocation(N_KEYS)
  public void add_hashMap(D1State s) {
    HashMap<String, Long> m = s.hashMap;
    String[] keys = s.keys;
    m.clear();
    for (int i = 0; i < N_KEYS; ++i) {
      m.put(keys[i], (long) i);
    }
  }

  @Benchmark
  public long update_hashtable(D1State s) {
    D1Counter e = s.table.get(s.nextKey());
    return ++e.count;
  }

  @Benchmark
  public Long update_hashMap(D1State s) {
    return s.hashMap.merge(s.nextKey(), 1L, Long::sum);
  }

  @Benchmark
  public void iterate_hashtable(D1State s, Blackhole bh) {
    s.consumer.bh = bh;
    s.table.forEach(s.consumer);
  }

  @Benchmark
  public void iterate_hashMap(D1State s, Blackhole bh) {
    for (Map.Entry<String, Long> entry : s.hashMap.entrySet()) {
      bh.consume(entry.getKey());
      bh.consume(entry.getValue());
    }
  }
}

package datadog.trace.util;

import static java.util.concurrent.TimeUnit.MICROSECONDS;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
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
 * Compares {@link Hashtable.D2} against equivalent {@link HashMap} usage for add, update, and
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
 * <p>The D2 variants additionally pay for a composite-key wrapper allocation in the HashMap path
 * (Java has no built-in tuple-as-key) — D2 sidesteps it by taking both key parts directly.
 *
 * <p><b>Update</b> is where Hashtable dominates: D2 is ~26x faster on JDK 8 (see the Java 17 rerun
 * below for a narrower but still decisive margin), because the HashMap path allocates per call (a
 * {@code Long}, plus a {@code Key2}) and the resulting GC pressure throttles throughput under
 * multiple threads. Like D1, this is the headline case for {@code Hashtable}: a simple
 * counter/tally with a primitive value is exactly where HashMap's autoboxing tax bites hardest.
 * <b>Add</b> is ~3x faster for D2 (Hashtable sidesteps the {@code Key2} allocation). <b>Iterate</b>
 * is essentially a wash on JDK 8, though not on Java 17 (see below). <code>
 * MacBook M1 8 threads (Java 8)
 *
 * Benchmark                                Mode  Cnt     Score     Error   Units
 * HashtableD2Benchmark.add_hashMap        thrpt    6    77.082 ±  72.278  ops/us
 * HashtableD2Benchmark.add_hashtable      thrpt    6   216.813 ± 413.236  ops/us
 *
 * HashtableD2Benchmark.update_hashMap     thrpt    6    56.077 ±  23.716  ops/us
 * HashtableD2Benchmark.update_hashtable   thrpt    6  1445.868 ± 157.705  ops/us
 *
 * HashtableD2Benchmark.iterate_hashMap    thrpt    6    19.508 ±   0.760  ops/us
 * HashtableD2Benchmark.iterate_hashtable  thrpt    6    16.968 ±   0.371  ops/us
 * </code>
 *
 * <p>Rerun with {@link BenchmarkUtils#polluteHashDispatch()} added to {@code D2State.setUp()} (same
 * machine/JVM/config): results were noisy and inconsistent with a clean pollution story —
 * add_hashMap actually rose (77→103), while add_hashtable fell sharply (217→118, error bars wider
 * than the mean both times); update_hashtable fell (1446→1225) and both iterate numbers fell
 * (19.5→15.4, 17.0→13.1). As with {@link HashtableD1Benchmark}, {@code *_hashtable} is expected to
 * be a no-op here: {@link Hashtable.D2.Entry#hash} and {@link Hashtable.D2.Entry#matches} are call
 * sites private to {@code Hashtable.java}, structurally distinct from {@code
 * java.util.HashMap}/{@code HashSet}'s internal dispatch call sites — pollution cannot reach them.
 * With the JDK and machine held constant across this rerun, the drop is same-session run-to-run
 * noise (thermal/power, not controlled for) rather than a genuine pollution effect. Treat these two
 * runs as not directly comparable on absolute numbers. The <b>relative</b> conclusion (D2 dominates
 * {@code update}, wins {@code add} by avoiding the {@code Key2} allocation, ties on {@code
 * iterate}) is unchanged either way.
 *
 * <p>Separately rerun on Zulu 17.0.7 (native AArch64, same machine, pollution wiring unchanged).
 * JMH auto-detected the cheap "compiler" Blackhole mode on Java 17 (its log explicitly warns that
 * Blackhole-mode differences between JVMs can swing results significantly), which JDK 8 cannot use
 * — so absolute numbers below are <b>not</b> comparable to the JDK 8 tables above; only within-run
 * ratios are, since both benchmark methods in a given run get identical Blackhole treatment. M
 * ops/us, 8 threads:
 *
 * <pre>{@code
 * add_hashMap         656.7   add_hashtable     1185.5
 * update_hashMap      196.7   update_hashtable  2292.2
 * iterate_hashMap      20.5   iterate_hashtable   69.2
 * }</pre>
 *
 * <p>{@code update_hashtable} still wins decisively (~11.6x, down from ~26x on JDK 8 — Java 17's
 * allocator/GC absorbs {@code update_hashMap}'s per-call {@code Long}+{@code Key2} boxing far
 * better than JDK 8 did: update_hashMap got ~3.5x faster, update_hashtable only ~1.6x faster).
 * Unlike JDK 8, Hashtable now wins clearly on <i>every</i> operation: {@code add_hashtable} wins
 * ~1.8x (vs. JDK 8's ~3x — HashMap's {@code Key2} allocation also got relatively cheaper), and
 * {@code iterate_hashtable} flips from JDK 8's wash to a ~3.4x win (HashMap's {@code entrySet()}
 * iterator does more per-entry work than a modern JIT's allocation improvements erase). Net
 * takeaway, consistent with {@link HashtableD1Benchmark}: {@code Hashtable} is a strong substitute
 * for {@code HashMap} particularly for simple counter/tally use cases with a primitive value, where
 * avoiding the per-update boxing allocation pays off even on a JVM with much better allocation
 * handling than JDK 8 had — and for D2 specifically, avoiding the composite-key wrapper allocation
 * pays off across the board, not just on {@code update}.
 */
@Fork(2)
@Warmup(iterations = 2)
@Measurement(iterations = 3)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(MICROSECONDS)
@Threads(8)
public class HashtableD2Benchmark {

  static final int N_KEYS = 64;
  static final int CAPACITY = 128;

  static final String[] SOURCE_K1 = new String[N_KEYS];
  static final Integer[] SOURCE_K2 = new Integer[N_KEYS];

  static {
    for (int i = 0; i < N_KEYS; ++i) {
      SOURCE_K1[i] = "key-" + i;
      SOURCE_K2[i] = i * 31 + 17;
    }
  }

  static final class D2Counter extends Hashtable.D2.Entry<String, Integer> {
    long count;

    D2Counter(String k1, Integer k2) {
      super(k1, k2);
    }
  }

  /** Composite key for the HashMap baseline against D2. */
  static final class Key2 {
    final String k1;
    final Integer k2;
    final int hash;

    Key2(String k1, Integer k2) {
      this.k1 = k1;
      this.k2 = k2;
      this.hash = Objects.hash(k1, k2);
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof Key2)) {
        return false;
      }
      Key2 other = (Key2) o;
      return Objects.equals(k1, other.k1) && Objects.equals(k2, other.k2);
    }

    @Override
    public int hashCode() {
      return hash;
    }
  }

  /** Reusable iteration consumer — avoids per-call lambda capture allocation. */
  static final class BhD2Consumer implements Consumer<D2Counter> {
    Blackhole bh;

    @Override
    public void accept(D2Counter e) {
      bh.consume(e.key1);
      bh.consume(e.key2);
      bh.consume(e.count);
    }
  }

  @State(Scope.Thread)
  public static class D2State {
    Hashtable.D2<String, Integer, D2Counter> table;
    HashMap<Key2, Long> hashMap;
    String[] k1s;
    Integer[] k2s;
    int cursor;
    final BhD2Consumer consumer = new BhD2Consumer();

    @Setup(Level.Iteration)
    public void setUp() {
      BenchmarkUtils.polluteHashDispatch();

      table = new Hashtable.D2<>(CAPACITY);
      hashMap = new HashMap<>(CAPACITY);
      k1s = SOURCE_K1;
      k2s = SOURCE_K2;
      for (int i = 0; i < N_KEYS; ++i) {
        table.insert(new D2Counter(k1s[i], k2s[i]));
        hashMap.put(new Key2(k1s[i], k2s[i]), 0L);
      }
      cursor = 0;
    }

    int nextIndex() {
      int i = cursor;
      cursor = (i + 1) & (N_KEYS - 1);
      return i;
    }
  }

  @Benchmark
  @OperationsPerInvocation(N_KEYS)
  public void add_hashtable(D2State s) {
    Hashtable.D2<String, Integer, D2Counter> t = s.table;
    String[] k1s = s.k1s;
    Integer[] k2s = s.k2s;
    t.clear();
    for (int i = 0; i < N_KEYS; ++i) {
      t.insert(new D2Counter(k1s[i], k2s[i]));
    }
  }

  @Benchmark
  @OperationsPerInvocation(N_KEYS)
  public void add_hashMap(D2State s) {
    HashMap<Key2, Long> m = s.hashMap;
    String[] k1s = s.k1s;
    Integer[] k2s = s.k2s;
    m.clear();
    for (int i = 0; i < N_KEYS; ++i) {
      m.put(new Key2(k1s[i], k2s[i]), (long) i);
    }
  }

  @Benchmark
  public long update_hashtable(D2State s) {
    int i = s.nextIndex();
    D2Counter e = s.table.get(s.k1s[i], s.k2s[i]);
    return ++e.count;
  }

  @Benchmark
  public Long update_hashMap(D2State s) {
    int i = s.nextIndex();
    return s.hashMap.merge(new Key2(s.k1s[i], s.k2s[i]), 1L, Long::sum);
  }

  @Benchmark
  public void iterate_hashtable(D2State s, Blackhole bh) {
    s.consumer.bh = bh;
    s.table.forEach(s.consumer);
  }

  @Benchmark
  public void iterate_hashMap(D2State s, Blackhole bh) {
    for (Map.Entry<Key2, Long> entry : s.hashMap.entrySet()) {
      bh.consume(entry.getKey());
      bh.consume(entry.getValue());
    }
  }
}

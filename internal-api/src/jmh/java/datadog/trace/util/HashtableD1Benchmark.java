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
 * below for a narrower but still decisive margin). D1 mutates a primitive counter in the existing
 * entry; the HashMap path boxes a {@code Long} on every {@code merge}. Measured with {@code -prof
 * gc} on Zulu 17, {@code update_hashMap} allocates 24.000 ± 0.001 B/op — exactly one boxed {@code
 * Long} (12-byte header plus an 8-byte value, aligned to 24) — against ≈0 B/op for {@code
 * update_hashtable}, and 801 collections over the run against none. The GC pressure is measured
 * rather than inferred from throughput. This is the headline case for {@code Hashtable}: a simple
 * counter/tally with a primitive value is exactly where HashMap's autoboxing tax bites hardest, and
 * {@code Hashtable.D1} sidesteps it entirely by mutating a field on the retrieved entry in place.
 * <b>Add</b> is roughly comparable — both allocate one entry per insert, and the error bars exceed
 * the means, so no precise comparison is possible there. <b>Iterate</b> is essentially a wash on
 * JDK 8, though not on Java 17 (see below). <code>
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
 * <p>Rerun with {@link BenchmarkUtils#warmUpHashDispatch} added to {@code D1State.setUp()} (same
 * machine/JVM/config): every number moved down somewhat (add_hashMap 188→101, update_hashtable
 * 1810→1465, iterate_hashtable 22→17 ops/us), including {@code *_hashtable}. That's expected to be
 * a no-op for {@code *_hashtable}: {@link Hashtable.D1.Entry#hash} and {@link
 * Hashtable.D1.Entry#matches} are call sites private to {@code Hashtable.java}, structurally
 * distinct from {@code java.util.HashMap}/{@code HashSet}'s internal {@code hashCode()}/{@code
 * equals()} call sites — JIT type profiles are keyed per call site, so {@code warmUpHashDispatch}
 * cannot reach them regardless of key-type overlap. It is equally a no-op for {@code *_hashMap}:
 * the keys come from {@code SOURCE_KEYS}, a {@code String[]}, and {@code String} is final, so C2
 * sharpens the {@code Object}-declared key to an exact type and devirtualizes {@code
 * hashCode()}/{@code equals()} without consulting the polluted profile. Pollution therefore cannot
 * explain a drop on either side. The JDK and machine were held constant, so what remains is
 * uncontrolled run-to-run variation.
 *
 * <p>The other candidate is that {@code warmUpHashDispatch}'s own heavy pre-measurement allocation
 * shifts GC state for the trial. Pollution used to run once per thread, so at {@code @Threads(8)}
 * it did eight times the work; it now runs once per JVM. Re-measuring across that 8x reduction
 * moved nothing: {@code update_hashMap} 686.0 to 689.6, {@code update_hashtable} 2770.7 to 2796.3,
 * {@code iterate_hashMap} 19.83 to 19.84, all well inside their intervals. So the <i>amount</i> of
 * setup allocation isn't a factor at this scale; whether a single pass affects the trial at all was
 * not tested, since both runs include one. The <b>relative</b> conclusion (D1 dominates {@code
 * update}, is roughly comparable on {@code add}, ties on {@code iterate}) is unchanged throughout.
 *
 * <p>Separately rerun on Zulu 17.0.7 (native AArch64, same machine, pollution wiring unchanged; JMH
 * auto-detected the cheap "compiler" Blackhole mode here, unlike JDK 8, so absolute numbers below
 * are not comparable to the JDK 8 tables above — see {@code HashtableD2Benchmark}'s javadoc for the
 * full caveat). ops/us, 8 threads:
 *
 * <pre>{@code
 * Benchmark            ops/us            B/op   gc.count
 * add_hashMap        1658.6 ± 190.5      32.0       1839
 * add_hashtable      1358.0 ± 213.6      40.0       1710
 * update_hashMap      689.6 ± 140.8      24.0        801
 * update_hashtable   2796.3 ±  79.7       ~0          ~0
 * iterate_hashMap      19.8 ±   0.3      40.0         76
 * iterate_hashtable    81.5 ±   2.5       ~0          ~0
 * }</pre>
 *
 * <p>Allocation is measured with {@code -prof gc} and decomposes exactly: 24 B/op for {@code
 * update_hashMap} is one boxed {@code Long}; 32 B/op for {@code add_hashMap} is one {@code
 * HashMap.Node}, with no box because {@code (long) i} for {@code i < 128} hits the {@code
 * Long.valueOf} cache; 40 B/op for {@code add_hashtable} is the {@code D1Counter} entry. The
 * hashtable's {@code update} and {@code iterate} paths allocate nothing at all, which is the point
 * of the design.
 *
 * <p>Within this run, {@code update_hashtable} wins by ~4.0x and {@code iterate_hashtable} by
 * ~4.1x. {@code add_hashMap} leads on the means (1658.6 vs 1358.0), but the error bars overlap, so
 * treat {@code add} as undecided rather than a HashMap win.
 *
 * <p>The {@code update} ratio depends heavily on the collector, so it is not a fixed property of
 * the two structures. A controlled follow-up on the same Zulu 17 build, with the Blackhole mode
 * forced to {@code FULL_DONTINLINE} for every configuration (separate invocations, so allow ~20%
 * run-to-run spread):
 *
 * <pre>{@code
 * Configuration              update_hashMap   update_hashtable   ratio
 * G1 (default)                522.4 ±  42.1     1408.0 ±  90.0    2.7x
 * G1, -XX:-EliminateAutoBox   536.5 ±  24.5     1689.6 ±  46.2    3.1x
 * -XX:+UseParallelGC          129.2 ±  36.8     1634.2 ±  19.3   12.6x
 * }</pre>
 *
 * <p>Disabling autobox elimination changes nothing for {@code update_hashMap} (still 24 B/op): the
 * {@code Long} escapes into the map, so there is no box to eliminate. Switching to ParallelGC cuts
 * {@code update_hashMap} ~4x while leaving the allocation-free {@code update_hashtable} where it
 * was, so the collector governs how expensive the per-update box is. The mechanism isn't isolated:
 * total {@code gc.time} is similar under both collectors (1106 vs 1226 ms) despite ParallelGC
 * running ~3x as many collections, so reported pause time alone doesn't account for the gap. The
 * forced Blackhole mode also compresses the ratios relative to the table above (2.7x vs 4.0x on
 * {@code update}, 1.5x vs 4.1x on {@code iterate}), since every arm pays a non-inlined call per
 * {@code consume}; the default-mode table is the one to quote.
 *
 * <p>Net takeaway: {@code Hashtable} is a strong substitute for {@code HashMap} for simple
 * counter/tally use cases with a primitive value. Avoiding the per-update boxing allocation wins
 * under G1, and wins by a much larger margin on a throughput collector.
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

    // Front-load pollution once per trial, entirely before JMH's warmup starts: JMH
    // injects the Blackhole straight into this setup method, so no per-benchmark
    // scratch state is needed.
    @Setup(Level.Trial)
    public void warmUpPollution(Blackhole bh) {
      BenchmarkUtils.warmUpHashDispatch(bh);
    }

    @Setup(Level.Iteration)
    public void setUp() {
      table = new Hashtable.D1<>(CAPACITY);
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

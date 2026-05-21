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
 * <p><b>Update</b> is where Hashtable dominates: D2 is ~26x faster, because the HashMap path
 * allocates per call (a {@code Long}, plus a {@code Key2}) and the resulting GC pressure throttles
 * throughput under multiple threads. <b>Add</b> is ~3x faster for D2 (Hashtable sidesteps the
 * {@code Key2} allocation). <b>Iterate</b> is essentially a wash — both are bucket walks. <code>
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

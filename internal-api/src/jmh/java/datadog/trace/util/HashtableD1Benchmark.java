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
 * <p><b>Update</b> is where Hashtable dominates: D1 is ~14x faster, because the HashMap path
 * allocates per call (a {@code Long}) and the resulting GC pressure throttles throughput under
 * multiple threads. <b>Add</b> is roughly comparable (both allocate one entry per insert).
 * <b>Iterate</b> is essentially a wash — both are bucket walks. <code>
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

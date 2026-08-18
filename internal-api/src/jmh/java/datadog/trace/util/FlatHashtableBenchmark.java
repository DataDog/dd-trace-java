package datadog.trace.util;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
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
 * Directional: is the {@link FlatHashtable} hit-path lookup (what a span pays per create, all hits
 * after warmup) cheap vs a {@link HashMap}? Concrete-typed {@code static final} helper so the
 * static-poly specialization is in play. One op = one pass over the op-name set. Single-threaded,
 * short — a rough signal, not a verdict (real numbers on the box).
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
@Threads(1)
public class FlatHashtableBenchmark {

  static final class StrHelper extends FlatHashtable.StringHelper<String> {
    @Override
    public boolean matches(String key, String value) {
      return key == value || key.equals(value);
    }

    @Override
    public String create(String key) {
      return key; // store the key itself as the (self-identifying) entry
    }
  }

  private static final StrHelper HELPER = new StrHelper();

  private static final String[] KEYS = {
    "servlet.request",
    "database.query",
    "http.request",
    "grpc.client",
    "kafka.produce",
    "kafka.consume",
    "jdbc.query",
    "spring.handler",
    "servlet.forward",
    "okhttp.request"
  };

  private String[] table;
  private Map<String, String> map;

  @Setup
  public void setup() {
    table = FlatHashtable.create(String.class, KEYS.length);
    map = new HashMap<>(KEYS.length * 2);
    for (String k : KEYS) {
      FlatHashtable.getOrCreate(table, k, HELPER);
      map.put(k, k);
    }
  }

  /** FlatHashtable all-hit lookups (concrete helper → specialized). */
  @Benchmark
  public void flatGet(Blackhole bh) {
    for (String k : KEYS) {
      bh.consume(FlatHashtable.get(table, k, HELPER));
    }
  }

  /** Steady-state span-create shape: get-then-getOrCreate, all hits. */
  @Benchmark
  public void flatGetOrCreate(Blackhole bh) {
    for (String k : KEYS) {
      bh.consume(FlatHashtable.getOrCreate(table, k, HELPER));
    }
  }

  /** Baseline: HashMap lookups over the same keys. */
  @Benchmark
  public void hashMapGet(Blackhole bh) {
    for (String k : KEYS) {
      bh.consume(map.get(k));
    }
  }
}

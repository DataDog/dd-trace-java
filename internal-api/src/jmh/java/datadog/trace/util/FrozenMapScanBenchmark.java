package datadog.trace.util;

import java.util.LinkedHashMap;
import java.util.Map;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Where {@link FrozenMap} should switch from scanning its keys to indexing them: {@code get} on
 * maps of {@code size} entries, built either way ({@code mode}).
 *
 * <p>Keys look like JSON attribute names, of mixed lengths. {@code get} looks up distinct but equal
 * key instances, the case for keys arriving from parsing; {@code get_sameKey} the instances the map
 * holds. Lookups cycle through every key, so a scan pays its average position.
 */
@Fork(3)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@State(Scope.Benchmark)
public class FrozenMapScanBenchmark {
  static final String[] NAMES = {
    "id", "name", "enabled", "color", "maxRetries", "timeoutMs", "variant", "weight",
    "description", "x", "threshold", "region", "tier", "createdAt", "tags", "limit"
  };

  @Param({"1", "2", "3", "4", "6", "8", "12", "16"})
  int size;

  @Param({"scan", "index"})
  String mode;

  FrozenMap<String, Integer> map;
  String[] keys;
  String[] equalKeys;

  @Setup(Level.Trial)
  public void setUp(Blackhole blackhole) {
    BenchmarkUtils.warmUpHashDispatch(blackhole);
    final Map<String, Integer> source = new LinkedHashMap<>();
    keys = new String[size];
    equalKeys = new String[size];
    for (int i = 0; i < size; i++) {
      source.put(NAMES[i], i);
      keys[i] = NAMES[i];
      equalKeys[i] = new String(NAMES[i]);
    }
    map = FrozenMap.copyOf(source, "scan".equals(mode) ? Integer.MAX_VALUE : 0);
  }

  /** Per-thread lookup cursor. */
  @State(Scope.Thread)
  public static class Cursor {
    int index;

    String next(final String[] keys) {
      if (++index >= keys.length) {
        index = 0;
      }
      return keys[index];
    }
  }

  @Benchmark
  public Integer get(Cursor cursor) {
    return map.get(cursor.next(equalKeys));
  }

  @Benchmark
  public Integer get_sameKey(Cursor cursor) {
    return map.get(cursor.next(keys));
  }
}

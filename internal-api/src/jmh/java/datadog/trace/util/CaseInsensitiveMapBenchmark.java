package datadog.trace.util;

import java.util.HashMap;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 *
 *
 * <ul>
 *   Benchmark for the trade-offs around case-insensitive Map look-ups, comparing:
 *   <li>TreeMap with a {@code String::compareToIgnoreCase} comparator — allocation-free, O(log n)
 *   <li>HashMap keyed on {@code toLowerCase()} — O(1) but allocates a folded String per look-up
 *   <li>FlatHashtable with a {@link FlatHashtable.CaseInsensitiveStringKeyStrategy} — O(1) probe,
 *       allocation-free (case folded inside hash/matches), value stored unboxed
 * </ul>
 *
 * <p><b>Takeaways.</b> FlatHashtable is ~2x the (previously recommended) TreeMap at the same zero
 * allocation, and matches HashMap's look-up throughput <i>without</i> HashMap's per-look-up folded
 * String (which drives the multi-threaded GC pressure). The case-insensitive hash is the
 * consistent-for-all-inputs two-way fold ({@link
 * datadog.trace.util.Strings#caseInsensitiveHashCode} — see its note); a cheaper ASCII-only fold
 * would recover a few percent for header-name-only hot paths, deliberately not the default. {@code
 * LOW_LOAD_FACTOR} makes no difference here (the fold, not the probe count, dominates), so the
 * default 0.5 is used.
 *
 * <p>Numbers below: MacBook M1, Zulu 21, per-thread lookup index, @Fork(5). <code>
 * 1 thread
 *
 * Benchmark                                     Mode  Cnt          Score        Error  Units
 * create_flatHashtable                         thrpt   15     3723141.4 ±    63717.9  ops/s
 * create_hashMap                               thrpt   15      905452.5 ±    16561.3  ops/s
 * create_treeMap                               thrpt   15     1208339.4 ±    84364.2  ops/s
 *
 * lookup_flatHashtable                         thrpt   15    75874505.0 ±  3722582.3  ops/s
 * lookup_flatHashtable_lowLoad                 thrpt   15    75686682.1 ±  1879579.4  ops/s
 * lookup_hashMap                               thrpt   15    80319813.9 ±  7410634.9  ops/s
 * lookup_treeMap                               thrpt   15    45926358.7 ±  1917349.2  ops/s
 * </code> <code>
 * 8 threads (with -prof gc; alloc = gc.alloc.rate.norm)
 *
 * Benchmark                          Mode  Cnt          Score         Error  Units      alloc
 * lookup_flatHashtable              thrpt   15  558144937.2 ±  22797680.7  ops/s     ~0 B/op
 * lookup_flatHashtable_lowLoad      thrpt   15  564984154.1 ±  25899687.5  ops/s     ~0 B/op
 * lookup_hashMap                    thrpt   15  529773720.8 ±  82928000.6  ops/s   24.0 B/op (151 GCs)
 * lookup_treeMap                    thrpt   15  262110611.8 ±  30486484.7  ops/s     ~0 B/op
 * </code>
 */
@Fork(2)
@Warmup(iterations = 2)
@Measurement(iterations = 3)
@Threads(8)
@State(Scope.Thread)
public class CaseInsensitiveMapBenchmark {
  static final String[] PREFIXES = {"foo", "bar", "baz", "quux"};

  static final int NUM_SUFFIXES = 4;

  static <T> T init(Supplier<T> supplier) {
    return supplier.get();
  }

  static final String[] UPPER_PREFIXES =
      init(
          () -> {
            String[] upperPrefixes = new String[PREFIXES.length];
            for (int i = 0; i < PREFIXES.length; ++i) {
              upperPrefixes[i] = PREFIXES[i].toUpperCase();
            }
            return upperPrefixes;
          });

  static final String[] LOOKUP_KEYS =
      init(
          () -> {
            ThreadLocalRandom curRandom = ThreadLocalRandom.current();

            String[] keys = new String[32];
            for (int i = 0; i < keys.length; ++i) {
              int prefixIndex = curRandom.nextInt(PREFIXES.length);
              boolean toUpper = curRandom.nextBoolean();
              int suffixIndex = curRandom.nextInt(NUM_SUFFIXES + 1);

              String key = PREFIXES[prefixIndex] + "-" + suffixIndex;
              keys[i] = toUpper ? key.toUpperCase() : key.toLowerCase();
            }
            return keys;
          });

  // Per-thread (@State(Scope.Thread)) so cycling the lookup key doesn't contend a shared counter.
  // The maps stay static/shared (read-only after class-init); only the index is per-thread. A
  // shared
  // counter's cache-line ping-pong would floor the fastest lookups (the flat probe) at @Threads(8),
  // masking exactly the differences this benchmark compares.
  int lookupIndex = 0;

  String nextLookupKey() {
    int localIndex = ++lookupIndex;
    if (localIndex >= LOOKUP_KEYS.length) {
      lookupIndex = localIndex = 0;
    }
    return LOOKUP_KEYS[localIndex];
  }

  @Benchmark
  public void create_baseline(Blackhole blackhole) {
    for (int suffix = 0; suffix < NUM_SUFFIXES; ++suffix) {
      for (String prefix : PREFIXES) {
        blackhole.consume(prefix + "-" + suffix);
        blackhole.consume(Integer.valueOf(suffix));
      }
    }
    for (int suffix = 0; suffix < NUM_SUFFIXES; suffix += 2) {
      for (String prefix : UPPER_PREFIXES) {
        blackhole.consume(prefix + "-" + suffix);
        blackhole.consume(Integer.valueOf(suffix + 1));
      }
    }
  }

  @Benchmark
  public void lookup_baseline(Blackhole blackhole) {
    blackhole.consume(nextLookupKey());
  }

  @Benchmark
  public HashMap<String, Integer> create_hashMap() {
    return _create_hashMap();
  }

  static HashMap<String, Integer> _create_hashMap() {
    HashMap<String, Integer> map = new HashMap<>();
    for (int suffix = 0; suffix < NUM_SUFFIXES; ++suffix) {
      for (String prefix : PREFIXES) {
        map.put(
            (prefix + "-" + suffix).toLowerCase(),
            suffix); // arguable, but real caller probably doesn't know the case ahead-of-time
      }
    }
    for (int suffix = 0; suffix < NUM_SUFFIXES; suffix += 2) {
      for (String prefix : UPPER_PREFIXES) {
        map.put((prefix + "-" + suffix).toLowerCase(), suffix + 1);
      }
    }
    return map;
  }

  static final HashMap<String, Integer> HASH_MAP = _create_hashMap();

  @Benchmark
  public Integer lookup_hashMap() {
    // This benchmark is still "correct" in multi-threaded context,
    // Map is populated under the class initialization lock and not changed thereafter
    return HASH_MAP.get(nextLookupKey().toLowerCase());
  }

  @Benchmark
  public TreeMap<String, Integer> create_treeMap() {
    return _create_treeMap();
  }

  static TreeMap<String, Integer> _create_treeMap() {
    TreeMap<String, Integer> map = new TreeMap<>(String::compareToIgnoreCase);
    for (int suffix = 0; suffix < NUM_SUFFIXES; ++suffix) {
      for (String prefix : PREFIXES) {
        map.put(prefix + "-" + suffix, suffix);
      }
    }
    for (int suffix = 0; suffix < NUM_SUFFIXES; suffix += 2) {
      for (String prefix : UPPER_PREFIXES) {
        map.put(prefix + "-" + suffix, suffix + 1);
      }
    }
    return map;
  }

  static final TreeMap<String, Integer> TREE_MAP = _create_treeMap();

  @Benchmark
  public Integer lookup_treeMap() {
    // This benchmark is still "correct" in multi-threaded context,
    // Map is populated under the initial class initialization lock and not changed thereafter
    return TREE_MAP.get(nextLookupKey());
  }

  // FlatHashtable with a case-insensitive KeyStrategy: the strategy folds case inside hash/matches,
  // so lookups are O(1) (single probe) AND allocation-free (no String::to<X>Case) — TreeMap's zero-
  // alloc property without TreeMap's O(log n) comparison walk. Value is stored unboxed. Read-only
  // after build, so reads are lock-free (see FlatHashtable / ThreadSafeMapBenchmark).
  static final class CIEntry extends FlatHashtable.Entry {
    final String key; // original case preserved
    final int value;

    CIEntry(String key, long hash, int value) {
      super(hash); // cache the (char-by-char) case-insensitive hash
      this.key = key;
      this.value = value;
    }
  }

  // Dogfoods the shared toolbox pieces: the CI hash is Strings.caseInsensitiveHashCode (sealed by
  // CaseInsensitiveStringStrategy), the table owns the spread. Only matches/hashOf are bespoke.
  static final class CaseInsensitiveKeyStrategy
      extends FlatHashtable.CaseInsensitiveStringStrategy<CIEntry> {
    static final CaseInsensitiveKeyStrategy INSTANCE = new CaseInsensitiveKeyStrategy();

    private CaseInsensitiveKeyStrategy() {}

    @Override
    public boolean matches(CIEntry entry, String key) {
      return key.equalsIgnoreCase(entry.key); // case-folded, allocation-free
    }

    @Override
    public long hashOf(CIEntry entry) {
      return entry.hash; // CIEntry caches its (raw, case-insensitive) hash
    }
  }

  static CIEntry[] _create_flat(float loadFactor) {
    // 16 distinct case-insensitive keys (foo-0..quux-3).
    CIEntry[] table =
        FlatHashtable.create(CIEntry.class, PREFIXES.length * NUM_SUFFIXES, loadFactor);
    for (int suffix = 0; suffix < NUM_SUFFIXES; ++suffix) {
      for (String prefix : PREFIXES) {
        String key = prefix + "-" + suffix;
        long hash = CaseInsensitiveKeyStrategy.INSTANCE.hashKey(key);
        FlatHashtable.insert(
            table, new CIEntry(key, hash, suffix), CaseInsensitiveKeyStrategy.INSTANCE);
      }
    }
    // The HashMap/TreeMap builds' second loop (UPPER_PREFIXES, suffix 0 & 2) only OVERWRITES values
    // case-insensitively — it adds no new keys, and values don't affect lookup throughput — so the
    // read set is these same 16 keys.
    return table;
  }

  @Benchmark
  public CIEntry[] create_flatHashtable() {
    return _create_flat(FlatHashtable.DEFAULT_LOAD_FACTOR);
  }

  static final CIEntry[] FLAT_TABLE = _create_flat(FlatHashtable.DEFAULT_LOAD_FACTOR);
  static final CIEntry[] FLAT_TABLE_LOW = _create_flat(FlatHashtable.LOW_LOAD_FACTOR);

  @Benchmark
  public CIEntry lookup_flatHashtable() {
    // Lock-free, allocation-free, single-probe case-insensitive lookup.
    return FlatHashtable.get(FLAT_TABLE, nextLookupKey(), CaseInsensitiveKeyStrategy.INSTANCE);
  }

  @Benchmark
  public CIEntry lookup_flatHashtable_lowLoad() {
    // Same, but at LOW_LOAD_FACTOR (4x): does the sparser table shave probes for the (mostly
    // hash-fold-dominated) CI lookup, or is it a wash? — see the delta to lookup_flatHashtable.
    return FlatHashtable.get(FLAT_TABLE_LOW, nextLookupKey(), CaseInsensitiveKeyStrategy.INSTANCE);
  }

  // TODO: Add ConcurrentSkipListMap & synchronized HashMap & TreeMap
}

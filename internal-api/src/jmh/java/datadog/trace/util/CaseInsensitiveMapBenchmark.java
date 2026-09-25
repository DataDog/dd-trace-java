package datadog.trace.util;

import java.util.HashMap;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * <ul>
 *   Benchmark for the trade-offs around case-insensitive Map look-ups, comparing:
 *   <li>TreeMap with a {@code String::compareToIgnoreCase} comparator — allocation-free, O(log n)
 *   <li>HashMap keyed on {@code toLowerCase()} — O(1) but allocates a folded String per look-up
 *   <li>FlatHashtable with a {@link FlatHashtable.CaseInsensitiveStringStrategy} — O(1) probe,
 *       allocation-free (case folded inside hash/matches), value stored unboxed
 * </ul>
 *
 * <p><b>Takeaways.</b> FlatHashtable is ~1.8x the (previously recommended) TreeMap at the same zero
 * allocation, but — see the revised takeaway below — trails HashMap's look-up throughput; its case
 * is the allocation win (no per-look-up folded String, which drives the multi-threaded GC pressure
 * HashMap pays), not a throughput win. The case-insensitive hash is the consistent-for-all-inputs
 * two-way fold ({@link datadog.trace.util.Strings#caseInsensitiveHashCode} — see its note); a
 * cheaper ASCII-only fold would recover a few percent for header-name-only hot paths, deliberately
 * not the default. {@code LOW_LOAD_FACTOR} makes no difference here (the fold, not the probe count,
 * dominates), so the default 0.5 is used.
 *
 * <p>Java 17 results (MacBook M1, {@code @Fork(5)}, {@code @Threads(8)}) with the front-loaded
 * {@link BenchmarkUtils#warmUpHashDispatch} pollution design (M ops/s):
 *
 * <pre>{@code
 * create_baseline        25.2    create_flatHashtable    15.3
 * create_hashMap          7.3    create_treeMap           8.4
 *
 * lookup_baseline       2760.8   lookup_flatHashtable    380.3
 * lookup_flatHashtable_lowLoad  430.7  lookup_hashMap    488.5
 * lookup_treeMap         214.7
 * }</pre>
 *
 * <p>{@code lookup_flatHashtable}/{@code lookup_hashMap}/{@code lookup_treeMap} carry error bars of
 * ~13-17% of their means at {@code @Fork(5)} (down from 44-66% at {@code @Fork(2)}, which wasn't
 * decisive) — tight enough that {@code hashMap}'s lead over {@code flatHashtable} (488.5 vs 380.3,
 * ~28%) is a real, if not perfectly clean-cut, result rather than noise.
 *
 * <p><b>Takeaway, revised.</b> {@code HashMap} keyed on {@code toLowerCase()} is faster than {@code
 * FlatHashtable} for this lookup shape, not merely comparable to it as earlier (noisier) runs
 * suggested. {@code FlatHashtable} still wins on allocation — it is the zero-allocation option, and
 * that stays true regardless of the throughput ordering — but the throughput case for it over
 * {@code HashMap} on case-insensitive lookups does not hold up under this rerun. {@code TreeMap}
 * remains the slowest of the three at every fork count measured.
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

  // Front-load pollution once per trial, entirely before JMH's warmup starts: JMH
  // injects the Blackhole straight into this setup method, so no per-benchmark
  // scratch state is needed.
  @Setup(Level.Trial)
  public void warmUpPollution(Blackhole bh) {
    BenchmarkUtils.warmUpHashDispatch(bh);
  }

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
    int value; // mutable: the collision loop below overwrites it on a hit, mirroring put()

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

  // Never fires here (the mirror loop below only hits already-present keys), so it neither
  // allocates nor captures; the value is unused. Kept static/non-capturing to avoid per-call cost.
  static final FlatHashtable.CreateStrategy<CIEntry, String> CI_CREATE =
      key -> new CIEntry(key, CaseInsensitiveKeyStrategy.INSTANCE.hashKey(key), 0);

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
    // Mirror the HashMap/TreeMap builds' second loop (UPPER_PREFIXES, suffix 0 & 2): 8 case-
    // insensitive collisions. getOrCreate finds the already-present lower-case entry (a hit -> the
    // create never fires, nothing allocates) and then the value is overwritten explicitly -- getOr-
    // Create itself never updates an existing entry, so without this the FlatHashtable arm would do
    // less work (and end up with different final values) than the maps' overwriting put(), a false
    // performance advantage. With the overwrite, all three create arms perform the same 24
    // operations and end up with the same final values.
    for (int suffix = 0; suffix < NUM_SUFFIXES; suffix += 2) {
      for (String prefix : UPPER_PREFIXES) {
        String key = prefix + "-" + suffix;
        CIEntry entry =
            FlatHashtable.getOrCreate(table, key, CaseInsensitiveKeyStrategy.INSTANCE, CI_CREATE);
        entry.value = suffix + 1;
      }
    }
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

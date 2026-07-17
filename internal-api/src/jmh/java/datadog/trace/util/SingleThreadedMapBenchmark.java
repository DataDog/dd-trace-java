package datadog.trace.util;

import datadog.trace.api.TagMap;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
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
 * Benchmark for single-threaded (uncontended) map usage: each thread builds, mutates, reads, and
 * discards its <i>own</i> maps. Models the common tracer pattern of assembling a short-lived map
 * (e.g. span tags) on a single thread.
 *
 * <p>State is per-thread ({@link Scope#Thread}) so no map is ever shared — the read-mostly shared
 * case lives in {@link ImmutableMapBenchmark}, and the contended case in the {@code
 * ConcurrentHashtable} / {@code ThreadSafeMap} suites. Running at {@code @Threads(8)} keeps
 * allocation / GC interactions visible without introducing lock contention.
 *
 * <p>Comparing different Map types:
 *
 * <ul>
 *   <li>(RECOMMENDED) HashMap — fastest general-purpose lookups
 *   <li>(RECOMMENDED) TagMap — preferred for storing tags; excels at primitives, copying, and
 *       builder idioms
 *   <li>TreeMap — when a custom Comparator is needed (see CaseInsensitiveMapBenchmark)
 *   <li>LinkedHashMap — only when insertion-order iteration is required; cost is paid at
 *       construction and in per-entry memory
 *   <li>FlatHashtable — a find-or-create table over self-contained entries (not a general Map: no
 *       arbitrary put/remove). Compared here on the ops it does support — build, get, iterate —
 *       where its self-contained entry stores the value <i>unboxed</i> (one object per key, no
 *       {@code Integer}). Fixed-capacity, so it must be sized to the working set up front.
 * </ul>
 *
 * <p><b>Uncontended synchronization tax.</b> A {@link Collections#synchronizedMap} case is included
 * to measure what synchronization costs when there is <i>no</i> contention: because each thread
 * owns its synchronized map, the monitor is only ever locked by one thread. On JVMs with biased
 * locking (Java &le; 11 by default) repeated same-thread locking should be nearly free; on Java 15+
 * (biased locking disabled by default, JEP 374) it pays the full uncontended CAS. The
 * unsynchronized {@code hashMap} {@code get}/{@code iterate} methods are the in-harness baseline;
 * the tax is the delta to the {@code synchronizedHashMap} equivalents. Comparing across JVM
 * versions at stock flags shows the biased-locking effect. (Results pending a fresh multi-JVM run.)
 */
@Fork(2)
@Warmup(iterations = 2)
@Measurement(iterations = 3)
@Threads(8)
@State(Scope.Thread)
public class SingleThreadedMapBenchmark {
  static final String[] INSERTION_KEYS = {
    "foo", "bar", "baz", "quux", "foobar", "foobaz", "key0", "key1", "key2", "key3"
  };

  // Distinct String instances so lookups exercise equals(), not identity.
  static final String[] EQUAL_KEYS = newEqualKeys();

  static String[] newEqualKeys() {
    String[] keys = new String[INSERTION_KEYS.length];
    for (int i = 0; i < INSERTION_KEYS.length; ++i) {
      keys[i] = new String(INSERTION_KEYS[i]);
    }
    return keys;
  }

  static void fill(Map<String, Integer> map) {
    for (int i = 0; i < INSERTION_KEYS.length; ++i) {
      map.put(INSERTION_KEYS[i], i);
    }
  }

  static TagMap fillTagMap(TagMap map) {
    for (int i = 0; i < INSERTION_KEYS.length; ++i) {
      map.set(INSERTION_KEYS[i], i); // primitive support
    }
    return map;
  }

  // FlatHashtable is a find-or-create table over self-contained entries — no arbitrary put/remove,
  // so only the comparable ops appear here: build (via the comparison-free insert of distinct
  // keys),
  // get, and iterate. Its entry carries the value UNBOXED (no Integer), one object per key.
  static final class IntEntry {
    final String key;
    final int value;

    IntEntry(String key, int value) {
      this.key = key;
      this.value = value;
    }
  }

  static final class IntEntryKeyStrategy extends FlatHashtable.StringKeyStrategy<IntEntry> {
    // Canonical exact-typed singleton: one instance, private ctor => the static-poly discipline is
    // enforced by the class, not left to each caller to declare correctly.
    static final IntEntryKeyStrategy INSTANCE = new IntEntryKeyStrategy();

    private IntEntryKeyStrategy() {}

    @Override
    public boolean matches(String key, IntEntry entry) {
      return key.equals(entry.key);
    }

    @Override
    public long hashOf(IntEntry entry) {
      return hash(entry.key);
    }
  }

  // --- CHA-defeat decoys ---------------------------------------------------------------------
  // Never used to build a table; loaded (in setUp) only so KeyStrategy.hash and KeyStrategy.matches
  // each have >=2 concrete implementors. That denies C2 the single-implementor CHA devirtualization
  // of keyStrat.hash/matches inside get(). If the strategy calls still inline afterward, the win is
  // structural (the constant INSTANCE's exact type propagated through the inlined get), not a CHA
  // bet that would deopt when a second subclass loads.

  // Second StringKeyStrategy impl -> KeyStrategy.matches is now polymorphic.
  static final class DecoyStringKeyStrategy extends FlatHashtable.StringKeyStrategy<IntEntry> {
    static final DecoyStringKeyStrategy INSTANCE = new DecoyStringKeyStrategy();

    private DecoyStringKeyStrategy() {}

    @Override
    public boolean matches(String key, IntEntry entry) {
      return key == entry.key; // deliberately different body from IntEntryKeyStrategy
    }

    @Override
    public long hashOf(IntEntry entry) {
      return hash(entry.key);
    }
  }

  // Direct KeyStrategy impl with its own hash -> KeyStrategy.hash is now polymorphic too.
  static final class DecoyKeyStrategy extends FlatHashtable.KeyStrategy<String, IntEntry> {
    static final DecoyKeyStrategy INSTANCE = new DecoyKeyStrategy();

    private DecoyKeyStrategy() {}

    @Override
    public long hash(String key) {
      return key.length();
    }

    @Override
    public boolean matches(String key, IntEntry entry) {
      return key.equals(entry.key);
    }

    @Override
    public long hashOf(IntEntry entry) {
      return entry.key.length();
    }
  }

  // Referenced only so these three concrete KeyStrategy implementors load at benchmark class-init,
  // before the hot method compiles — see the CHA-defeat note above.
  @SuppressWarnings("unused")
  static final Object[] CHA_DEFEAT = {
    IntEntryKeyStrategy.INSTANCE, DecoyStringKeyStrategy.INSTANCE, DecoyKeyStrategy.INSTANCE
  };

  static IntEntry[] newFilledFlat() {
    // Sized to the key count (FlatHashtable is fixed-capacity, no resize): load factor <= 0.5.
    IntEntry[] table = FlatHashtable.create(IntEntry.class, INSERTION_KEYS.length);
    for (int i = 0; i < INSERTION_KEYS.length; ++i) {
      FlatHashtable.insert(table, new IntEntry(INSERTION_KEYS[i], i), IntEntryKeyStrategy.INSTANCE);
    }
    return table;
  }

  // Per-thread prebuilt maps for the read + clone benchmarks (built once per trial, per thread).
  HashMap<String, Integer> hashMap;
  Map<String, Integer> synchronizedHashMap;
  TreeMap<String, Integer> treeMap;
  LinkedHashMap<String, Integer> linkedHashMap;
  TagMap tagMap;
  IntEntry[] flatTable;
  int index = 0;

  @Setup(Level.Trial)
  public void setUp() {
    hashMap = new HashMap<>();
    fill(hashMap);
    synchronizedHashMap = Collections.synchronizedMap(new HashMap<>(hashMap));
    treeMap = new TreeMap<>();
    fill(treeMap);
    linkedHashMap = new LinkedHashMap<>();
    fill(linkedHashMap);
    tagMap = fillTagMap(TagMap.create());
    flatTable = newFilledFlat();
  }

  String nextLookupKey() {
    if (++index >= EQUAL_KEYS.length) index = 0;
    return EQUAL_KEYS[index];
  }

  // ---- construction: build cost + allocation ----

  @Benchmark
  public Map<String, Integer> create_hashMap() {
    HashMap<String, Integer> map = new HashMap<>();
    fill(map);
    return map;
  }

  @Benchmark
  public Map<String, Integer> create_hashMap_sized() {
    // Sizing is preferable for large maps, but in practice most of our maps fall within the
    // default.
    HashMap<String, Integer> map = new HashMap<>(INSERTION_KEYS.length);
    fill(map);
    return map;
  }

  @Benchmark
  public Map<String, Integer> create_synchronizedHashMap() {
    Map<String, Integer> map = Collections.synchronizedMap(new HashMap<>());
    fill(map);
    return map;
  }

  @Benchmark
  public TreeMap<String, Integer> create_treeMap() {
    TreeMap<String, Integer> map = new TreeMap<>();
    fill(map);
    return map;
  }

  @Benchmark
  public LinkedHashMap<String, Integer> create_linkedHashMap() {
    LinkedHashMap<String, Integer> map = new LinkedHashMap<>();
    fill(map);
    return map;
  }

  @Benchmark
  public TagMap create_tagMap() {
    return fillTagMap(TagMap.create());
  }

  @Benchmark
  public TagMap create_tagMap_via_ledger() {
    TagMap.Ledger ledger = TagMap.ledger();
    for (int i = 0; i < INSERTION_KEYS.length; ++i) {
      ledger.set(INSERTION_KEYS[i], i); // primitive support
    }
    return ledger.build();
  }

  @Benchmark
  public IntEntry[] create_flatHashtable() {
    return newFilledFlat();
  }

  // ---- copy ----

  @Benchmark
  public Map<String, Integer> clone_hashMap() {
    return new HashMap<>(hashMap);
  }

  @Benchmark
  public Map<String, Integer> clone_synchronizedHashMap() {
    return Collections.synchronizedMap(new HashMap<>(synchronizedHashMap));
  }

  @Benchmark
  public TreeMap<String, Integer> clone_treeMap() {
    TreeMap<String, Integer> map = new TreeMap<>();
    map.putAll(treeMap);
    return map;
  }

  @Benchmark
  public LinkedHashMap<String, Integer> clone_linkedHashMap() {
    return new LinkedHashMap<>(linkedHashMap);
  }

  @Benchmark
  public TagMap clone_tagMap() {
    return tagMap.copy();
  }

  // ---- read: unsynchronized baseline vs uncontended synchronized (biased-locking story) ----

  @Benchmark
  public Integer get_hashMap() {
    return hashMap.get(nextLookupKey());
  }

  @Benchmark
  public Integer get_synchronizedHashMap() {
    return synchronizedHashMap.get(nextLookupKey());
  }

  @Benchmark
  public IntEntry get_flatHashtable() {
    return FlatHashtable.get(flatTable, nextLookupKey(), IntEntryKeyStrategy.INSTANCE);
  }

  @Benchmark
  public void iterate_hashMap(Blackhole blackhole) {
    for (Map.Entry<String, Integer> entry : hashMap.entrySet()) {
      blackhole.consume(entry.getKey());
      blackhole.consume(entry.getValue());
    }
  }

  @Benchmark
  public void iterate_synchronizedHashMap(Blackhole blackhole) {
    // Collections.synchronizedMap requires the caller to synchronize during iteration; this is the
    // correct usage and measures one (uncontended) monitor acquire around the traversal.
    synchronized (synchronizedHashMap) {
      for (Map.Entry<String, Integer> entry : synchronizedHashMap.entrySet()) {
        blackhole.consume(entry.getKey());
        blackhole.consume(entry.getValue());
      }
    }
  }

  @Benchmark
  public void iterate_flatHashtable(Blackhole blackhole) {
    // Context-passing forEach: blackhole rides through as context, so the lambda doesn't capture.
    FlatHashtable.forEach(
        flatTable,
        blackhole,
        (bh, e) -> {
          bh.consume(e.key);
          bh.consume(e.value);
        });
  }
}

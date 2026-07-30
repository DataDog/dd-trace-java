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
 *   <li>LightMap — a tiny, entry-less open-addressed map for short-lived, miss-dominated maps that
 *       do not need the {@code java.util.Map} interface; allocation-light, and its for-each
 *       iteration is shaped so escape analysis eliminates the iterator (see {@code iterate_lightMap})
 *   <li>TreeMap — when a custom Comparator is needed (see CaseInsensitiveMapBenchmark)
 *   <li>LinkedHashMap — only when insertion-order iteration is required; cost is paid at
 *       construction and in per-entry memory
 * </ul>
 *
 * <p><b>Allocation-free iteration.</b> {@code iterate_lightMap} exercises the entry-less
 * {@link LightMap} for-each: the flyweight iterator is both the {@code Iterator} and the
 * {@code EntryReader} it yields, so with a concretely-typed map that lets it stay non-escaping,
 * escape analysis scalar-replaces it. Measured (JDK 17, {@code -prof gc}, {@code -t 1}) at
 * {@code gc.alloc.rate.norm ≈ 10^-5 B/op} -- i.e. zero. The point is <em>not</em> that this beats
 * {@code iterate_hashMap}: HashMap's iterator likewise scalar-replaces and reuses its stored
 * {@code Node} as the entry, so it is also ~0 B/op. The point is that LightMap's entry-less layout
 * (no per-entry object to hand out) costs nothing at iteration time -- the flyweight matches
 * HashMap's zero-alloc traversal rather than paying to materialize entries. Re-run with
 * {@code -prof gc} to confirm both arms stay at ~0 B/op.
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

  static LightMap<String, Integer> fillLightMap(LightMap<String, Integer> map) {
    for (int i = 0; i < INSERTION_KEYS.length; ++i) {
      map.set(INSERTION_KEYS[i], i);
    }
    return map;
  }

  // Per-thread prebuilt maps for the read + clone benchmarks (built once per trial, per thread).
  HashMap<String, Integer> hashMap;
  Map<String, Integer> synchronizedHashMap;
  TreeMap<String, Integer> treeMap;
  LinkedHashMap<String, Integer> linkedHashMap;
  TagMap tagMap;
  LightMap<String, Integer> lightMap;
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
    lightMap = fillLightMap(LightMap.createUncapped());
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
  public LightMap<String, Integer> create_lightMap() {
    return fillLightMap(LightMap.createUncapped());
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
  public Integer get_lightMap() {
    return lightMap.get(nextLookupKey());
  }

  @Benchmark
  public void iterate_hashMap(Blackhole blackhole) {
    for (Map.Entry<String, Integer> entry : hashMap.entrySet()) {
      blackhole.consume(entry.getKey());
      blackhole.consume(entry.getValue());
    }
  }

  @Benchmark
  public void iterate_lightMap(Blackhole blackhole) {
    // LightMap is concretely typed here so the for-each call sites devirtualize and inline; the
    // flyweight iterator never escapes this method, so escape analysis scalar-replaces it entirely
    // -- measured ~0 B/op under -prof gc. iterate_hashMap is the peer: it too is ~0 B/op (its
    // iterator scalar-replaces and reuses the stored Node as the entry), so this arm confirms the
    // entry-less flyweight matches that zero-alloc traversal rather than paying to materialize.
    for (LightMap.EntryReader<String, Integer> entry : lightMap) {
      blackhole.consume(entry.key());
      blackhole.consume(entry.value());
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
}

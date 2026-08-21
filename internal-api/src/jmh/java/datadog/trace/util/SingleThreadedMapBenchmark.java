package datadog.trace.util;

import datadog.trace.api.TagMap;
import datadog.trace.util.LightMap.AdaptiveSizingHint;
import datadog.trace.util.LightMap.EmbeddingSupport;
import datadog.trace.util.LightMap.EntryReader;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Param;
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
 * <p>{@code size} is swept via {@code @Param} ({@code 4, 8, 16, 32, 64, 256}) rather than fixed at
 * one count, so every benchmark runs once per size: 4 and 8 straddle {@code LightMap}'s {@code
 * DEFAULT_CAPACITY} seed, 16/32 cover the small-map regime the primitive targets, and 64/256 force
 * multiple grows well past it. Use this to see how the LightMap-vs-HashMap comparison (construction
 * cost, allocation, probe length) shifts as live entries grow, rather than reading a single
 * fixed-size snapshot as universal.
 *
 * <p>Comparing different Map types:
 *
 * <ul>
 *   <li>(RECOMMENDED) HashMap — fastest general-purpose lookups
 *   <li>(RECOMMENDED) TagMap — preferred for storing tags; excels at primitives, copying, and
 *       builder idioms
 *   <li>LightMap — a tiny, entry-less open-addressed map for short-lived, miss-dominated maps that
 *       do not need the {@code java.util.Map} interface; allocation-light, and its for-each
 *       iteration is shaped so escape analysis eliminates the iterator (see {@code
 *       iterate_lightMap})
 *   <li>TreeMap — when a custom Comparator is needed (see CaseInsensitiveMapBenchmark)
 *   <li>LinkedHashMap — only when insertion-order iteration is required; cost is paid at
 *       construction and in per-entry memory
 * </ul>
 *
 * <p><b>Allocation-free iteration.</b> {@code iterate_lightMap} exercises the entry-less {@link
 * LightMap} for-each: the flyweight iterator is both the {@code Iterator} and the {@code
 * EntryReader} it yields, so with a concretely-typed map that lets it stay non-escaping, escape
 * analysis scalar-replaces it. Measured (JDK 17, {@code -prof gc}, {@code -t 1}) at {@code
 * gc.alloc.rate.norm ≈ 10^-5 B/op} -- i.e. zero. The point is <em>not</em> that this beats {@code
 * iterate_hashMap}: HashMap's iterator likewise scalar-replaces and reuses its stored {@code Node}
 * as the entry, so it is also ~0 B/op. The point is that LightMap's entry-less layout (no per-entry
 * object to hand out) costs nothing at iteration time -- the flyweight matches HashMap's zero-alloc
 * traversal rather than paying to materialize entries. Re-run with {@code -prof gc} to confirm both
 * arms stay at ~0 B/op.
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
  // Genuinely static final and shared across every thread, matching the intended production usage
  // (LightMap.createUncappedAdaptiveSizingHint(), minted once per call site, not per thread). The
  // per-thread lightMapSizingHint field below gives each @Threads(8) worker its own private hint,
  // so it never exercises the concurrent seedSlots()/recordSlots() races that a real shared hint
  // sees; create_lightMap_adaptive_static does.
  static final AdaptiveSizingHint STATIC_LIGHT_MAP_SIZING_HINT =
      LightMap.createUncappedAdaptiveSizingHint();

  static String[] newInsertionKeys(int size) {
    String[] keys = new String[size];
    for (int i = 0; i < size; ++i) {
      keys[i] = "key" + i;
    }
    return keys;
  }

  // Distinct String instances so lookups exercise equals(), not identity.
  static String[] newEqualKeys(String[] insertionKeys) {
    String[] keys = new String[insertionKeys.length];
    for (int i = 0; i < insertionKeys.length; ++i) {
      keys[i] = new String(insertionKeys[i]);
    }
    return keys;
  }

  static void fill(Map<String, Integer> map, String[] keys) {
    for (int i = 0; i < keys.length; ++i) {
      map.put(keys[i], i);
    }
  }

  static TagMap fillTagMap(TagMap map, String[] keys) {
    for (int i = 0; i < keys.length; ++i) {
      map.set(keys[i], i); // primitive support
    }
    return map;
  }

  static LightMap<String, Integer> fillLightMap(LightMap<String, Integer> map, String[] keys) {
    for (int i = 0; i < keys.length; ++i) {
      map.set(keys[i], i);
    }
    return map;
  }

  // The "embedded" analog of fillLightMap: no LightMap wrapper object at all -- the caller owns a
  // raw Object[] spine and drives EmbeddingSupport static functions over it. set() returns the
  // (possibly grown) array, mirroring how a consumer that has "dropped a level" would hold it.
  static Object[] fillLightMapEmbedded(String[] keys) {
    Object[] data = null;
    for (int i = 0; i < keys.length; ++i) {
      data = EmbeddingSupport.set(data, keys[i], i);
    }
    return data;
  }

  // Embedded fill seeded from a self-tuning hint (spine counterpart of LightMap.create(hint)).
  static Object[] fillLightMapEmbedded(AdaptiveSizingHint hint, String[] keys) {
    Object[] data = null;
    for (int i = 0; i < keys.length; ++i) {
      data = EmbeddingSupport.set(hint, data, keys[i], i);
    }
    return data;
  }

  // Map size, swept so the LightMap-vs-HashMap comparison (construction cost, allocation, probe
  // length) can be read as a function of live entries rather than pinned to one arbitrarily chosen
  // count. 4 and 8 straddle LightMap's DEFAULT_CAPACITY seed; 16 and 32 cover the expected regime
  // for a small map; 64 and 256 exercise multiple grows well past it.
  @Param({"4", "8", "16", "32", "64", "256"})
  public int size;

  String[] insertionKeys;
  // Distinct String instances so lookups exercise equals(), not identity.
  String[] equalKeys;

  // Per-thread prebuilt maps for the read + clone benchmarks (built once per trial, per thread).
  HashMap<String, Integer> hashMap;
  Map<String, Integer> synchronizedHashMap;
  TreeMap<String, Integer> treeMap;
  LinkedHashMap<String, Integer> linkedHashMap;
  TagMap tagMap;
  LightMap<String, Integer> lightMap;
  // Minted once per thread and reused across every create_lightMap_adaptive invocation on that
  // thread. Warmup iterations let it converge to the fill size, so the measured creates seed a
  // right-sized table instead of resizing up from the small createUncapped() seed. Unlike
  // STATIC_LIGHT_MAP_SIZING_HINT above, this hint is never shared between threads.
  AdaptiveSizingHint lightMapSizingHint;
  // Prebuilt raw spine (no wrapper) for the embedded get / iterate arms.
  Object[] lightMapData;
  int index = 0;

  @Setup(Level.Trial)
  public void setUp() {
    insertionKeys = newInsertionKeys(size);
    equalKeys = newEqualKeys(insertionKeys);
    hashMap = new HashMap<>();
    fill(hashMap, insertionKeys);
    synchronizedHashMap = Collections.synchronizedMap(new HashMap<>(hashMap));
    treeMap = new TreeMap<>();
    fill(treeMap, insertionKeys);
    linkedHashMap = new LinkedHashMap<>();
    fill(linkedHashMap, insertionKeys);
    tagMap = fillTagMap(TagMap.create(), insertionKeys);
    lightMap = fillLightMap(LightMap.createUncapped(), insertionKeys);
    lightMapSizingHint = LightMap.createUncappedAdaptiveSizingHint();
    lightMapData = fillLightMapEmbedded(insertionKeys);
  }

  String nextLookupKey() {
    if (++index >= equalKeys.length) index = 0;
    return equalKeys[index];
  }

  // ---- construction: build cost + allocation ----

  @Benchmark
  public Map<String, Integer> create_hashMap() {
    HashMap<String, Integer> map = new HashMap<>();
    fill(map, insertionKeys);
    return map;
  }

  @Benchmark
  public Map<String, Integer> create_hashMap_sized() {
    // Sizing is preferable for large maps, but in practice most of our maps fall within the
    // default.
    HashMap<String, Integer> map = new HashMap<>(insertionKeys.length);
    fill(map, insertionKeys);
    return map;
  }

  @Benchmark
  public Map<String, Integer> create_synchronizedHashMap() {
    Map<String, Integer> map = Collections.synchronizedMap(new HashMap<>());
    fill(map, insertionKeys);
    return map;
  }

  @Benchmark
  public TreeMap<String, Integer> create_treeMap() {
    TreeMap<String, Integer> map = new TreeMap<>();
    fill(map, insertionKeys);
    return map;
  }

  @Benchmark
  public LinkedHashMap<String, Integer> create_linkedHashMap() {
    LinkedHashMap<String, Integer> map = new LinkedHashMap<>();
    fill(map, insertionKeys);
    return map;
  }

  @Benchmark
  public TagMap create_tagMap() {
    return fillTagMap(TagMap.create(), insertionKeys);
  }

  @Benchmark
  public TagMap create_tagMap_via_ledger() {
    TagMap.Ledger ledger = TagMap.ledger();
    for (int i = 0; i < insertionKeys.length; ++i) {
      ledger.set(insertionKeys[i], i); // primitive support
    }
    return ledger.build();
  }

  @Benchmark
  public LightMap<String, Integer> create_lightMap() {
    return fillLightMap(LightMap.createUncapped(), insertionKeys);
  }

  @Benchmark
  public LightMap<String, Integer> create_lightMap_adaptive() {
    // Same fill, but seeded from the self-tuning hint held across invocations -- isolates how much
    // of create_lightMap's cost is resize churn from the small createUncapped() seed.
    return fillLightMap(LightMap.create(lightMapSizingHint), insertionKeys);
  }

  @Benchmark
  public LightMap<String, Integer> create_lightMap_adaptive_static() {
    // Same fill as create_lightMap_adaptive, but seeded from STATIC_LIGHT_MAP_SIZING_HINT, a
    // single hint instance shared by every @Threads(8) worker -- the genuine "static final held
    // once per call site" usage the class doc recommends, including the seedSlots()/recordSlots()
    // races that come with sharing it across concurrently-running threads. Note this hint is also
    // shared *across* @Param size values within one fork, so its learned seed can carry over from
    // one size to the next -- a caveat specific to this arm, not to static hints in general.
    return fillLightMap(LightMap.create(STATIC_LIGHT_MAP_SIZING_HINT), insertionKeys);
  }

  @Benchmark
  public Object[] create_lightMap_embedded() {
    // No wrapper object -- build straight over a raw Object[] spine. The delta to create_lightMap
    // is
    // the LightMap wrapper's own allocation/overhead.
    return fillLightMapEmbedded(insertionKeys);
  }

  @Benchmark
  public Object[] create_lightMap_embedded_adaptive() {
    // Embedded + hint-seeded: the leanest create path (no wrapper, right-sized first table).
    return fillLightMapEmbedded(lightMapSizingHint, insertionKeys);
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
  public Object get_lightMap_embedded() {
    return EmbeddingSupport.get(lightMapData, nextLookupKey());
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
  public void iterate_lightMap_embedded(Blackhole blackhole) {
    // Embedded for-each over the raw spine via EmbeddingSupport.iterable(). This adds an Iterable
    // lambda on top of the flyweight iterator versus the object tier's direct iterator(); -prof gc
    // shows whether escape analysis still folds both away to ~0 B/op.
    for (EntryReader<String, Integer> entry :
        EmbeddingSupport.<String, Integer>iterable(lightMapData)) {
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

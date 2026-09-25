package datadog.trace.util;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import org.openjdk.jmh.annotations.CompilerControl;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Shared setup helpers for JMH benchmarks in this module.
 *
 * <p>{@link Blackhole} solves one correctness problem: it stops the JIT from proving a result is
 * dead and eliminating the code that produced it. It says nothing about a second, separate problem
 * -- whether a call site's receiver-type profile still looks like production once measurement
 * starts. A benchmark can consume every result through a {@code Blackhole} and still measure a
 * devirtualized, artificially monomorphic fast path that never occurs in the real system. The
 * pollution helpers here ({@link #warmUpHashDispatch}) exist to guard against that second problem;
 * they're not redundant with {@code Blackhole}, they cover the axis it doesn't.
 */
public final class BenchmarkUtils {
  private BenchmarkUtils() {}

  private static final Object[] DECOY_KEYS = {"decoy", 1, 1L, 1.0d, Boolean.TRUE, new Object()};

  /**
   * Number of pollution passes {@link #warmUpHashDispatch} runs before a trial starts. Driving
   * enough calls at {@code Level.Trial} -- entirely before JMH's warmup, let alone measurement,
   * starts -- gets HotSpot's tiered compiler through both C1 (tier 3, full profiling; default
   * invocation threshold in the hundreds) and C2 (tier 4; default thresholds in the thousands) on
   * the shared hash-dispatch call sites while decoy types are still part of their profile, so the
   * resulting compiled code keeps a genuine multi-receiver-type guard for the rest of the trial --
   * recompilation of already-C2-compiled code isn't triggered merely by one receiver type going
   * quiet, only by hitting an actual uncommon trap or similar deopt event, so it doesn't need
   * refreshing once compiled this way. This iteration count was picked by watching {@code
   * -XX:+PrintCompilation}/{@code -XX:+PrintInlining} during development and confirming both tiers
   * compile well before it's reached, with margin for ambient JVM-wide traffic on these shared JDK
   * call sites; it isn't verified at runtime (there's no portable, non-test-only JDK API for "is
   * this method at tier N").
   */
  private static final int WARM_UP_ITERATIONS = 50_000;

  /**
   * Exercises shared collection methods with several key classes before benchmark warmup.
   *
   * <p>HotSpot records receiver types at bytecode call sites, not per collection instance. Scratch
   * collections therefore contribute to the internal profiles used when compiling benchmark
   * lookups. Loading extra classes alone can defeat class-hierarchy analysis (optimization based on
   * the loaded implementations), but changing a receiver profile requires method calls.
   *
   * <p>{@link HashSet} uses {@link HashMap}'s lookup code, also used by {@code LinkedHashMap}.
   * {@link ConcurrentHashMap}, including its key-set views, has separate internal call sites. The
   * immutable set and map implementations selected by {@link CollectionUtils} are exercised
   * separately too; on older JDKs these helpers fall back to mutable collections.
   *
   * <p>{@link #polluteCompareToDispatch(Blackhole)} also exercises natural-order lookups in {@link
   * TreeSet}, {@link TreeMap}, and {@link ConcurrentSkipListMap}; {@link
   * #polluteComparatorDispatch(Blackhole)} exercises the same three collections' separate {@link
   * Comparator}-based call sites.
   *
   * <p>The benchmark's own collection call sites are not invoked here and remain free to specialize
   * for their receiver types. A call site's recorded type profile isn't threatened by which key
   * type happens to dominate traffic during or after warmup -- once HotSpot has recorded multiple
   * receiver types there, it stays megamorphic regardless of later call frequency. The risk this
   * class guards against is a statically deducible receiver type bypassing the profile entirely:
   * loading extra classes here defeats class-hierarchy analysis (optimization based on which
   * implementations are actually loaded), and {@link #distinctEqualCopy}'s {@code DONT_INLINE}
   * stops the JIT from tracing a decoy key's type back to its origin through static inference.
   */
  public static void warmUpHashDispatch(Blackhole bh) {
    for (int i = 0; i < WARM_UP_ITERATIONS; ++i) {
      polluteHashDispatch(bh);
      polluteCompareToDispatch(bh);
      polluteComparatorDispatch(bh);
    }
  }

  private static void polluteHashDispatch(Blackhole bh) {
    HashSet<Object> hashSet = new HashSet<>();
    ConcurrentHashMap<Object, Object> concurrentHashMap = new ConcurrentHashMap<>();
    Map<Object, Object> mapCopySource = new HashMap<>();
    for (Object key : DECOY_KEYS) {
      hashSet.add(distinctEqualCopy(key));
      bh.consume(hashSet.contains(distinctEqualCopy(key)));

      concurrentHashMap.put(distinctEqualCopy(key), key);
      bh.consume(concurrentHashMap.get(distinctEqualCopy(key)));

      mapCopySource.put(key, key);
    }
    bh.consume(hashSet);
    bh.consume(concurrentHashMap);

    Set<Object> immutableSet = CollectionUtils.tryMakeImmutableSet(Arrays.asList(DECOY_KEYS));
    Map<Object, Object> immutableMap = CollectionUtils.tryMakeImmutableMap(mapCopySource);
    for (Object key : DECOY_KEYS) {
      bh.consume(immutableSet.contains(distinctEqualCopy(key)));
      bh.consume(immutableMap.get(distinctEqualCopy(key)));
    }
    bh.consume(immutableSet);
    bh.consume(immutableMap);
  }

  /**
   * Exercises natural-order {@code compareTo} calls in {@link TreeSet}, {@link TreeMap}, and {@link
   * ConcurrentSkipListMap} with the default decoy keys.
   *
   * <p>Each key type uses a separate collection because the decoys are not mutually comparable. The
   * instances still execute the same internal call sites and contribute to their receiver profiles.
   * {@link #polluteComparatorDispatch(Blackhole)} is the counterpart for the separate call sites
   * these collections use when constructed with an explicit {@link java.util.Comparator}.
   *
   * <p>Keys that do not implement {@link Comparable}, including the plain {@code Object} decoy, are
   * skipped.
   */
  private static void polluteCompareToDispatch(Blackhole bh) {
    for (Object key : DECOY_KEYS) {
      if (!(key instanceof Comparable)) {
        continue;
      }

      TreeSet<Object> treeSet = new TreeSet<>();
      treeSet.add(key);
      bh.consume(treeSet.contains(distinctEqualCopy(key)));
      bh.consume(treeSet);

      TreeMap<Object, Object> treeMap = new TreeMap<>();
      treeMap.put(key, key);
      bh.consume(treeMap.get(distinctEqualCopy(key)));
      bh.consume(treeMap);

      ConcurrentSkipListMap<Object, Object> skipListMap = new ConcurrentSkipListMap<>();
      skipListMap.put(key, key);
      bh.consume(skipListMap.get(distinctEqualCopy(key)));
      bh.consume(skipListMap);
    }
  }

  /**
   * Exercises {@link Comparator}-based {@code compare} calls in {@link TreeSet}, {@link TreeMap},
   * and {@link ConcurrentSkipListMap} with the default decoy keys.
   *
   * <p>Constructing these collections with an explicit {@code Comparator} routes lookups through
   * internal call sites distinct from the no-arg, natural-ordering constructors {@link
   * #polluteCompareToDispatch(Blackhole)} exercises -- pollution there does not carry over here.
   * The comparator itself delegates to {@link Comparable#compareTo}, so this pass also keeps that
   * method's call site (invoked from inside the comparator, not from the collection directly) warm
   * across the same key types.
   *
   * <p>Keys that do not implement {@link Comparable}, including the plain {@code Object} decoy, are
   * skipped.
   */
  @SuppressWarnings({"unchecked", "rawtypes"})
  private static void polluteComparatorDispatch(Blackhole bh) {
    Comparator<Object> comparator = (a, b) -> ((Comparable) a).compareTo(b);
    for (Object key : DECOY_KEYS) {
      if (!(key instanceof Comparable)) {
        continue;
      }

      TreeSet<Object> treeSet = new TreeSet<>(comparator);
      treeSet.add(key);
      bh.consume(treeSet.contains(distinctEqualCopy(key)));
      bh.consume(treeSet);

      TreeMap<Object, Object> treeMap = new TreeMap<>(comparator);
      treeMap.put(key, key);
      bh.consume(treeMap.get(distinctEqualCopy(key)));
      bh.consume(treeMap);

      ConcurrentSkipListMap<Object, Object> skipListMap = new ConcurrentSkipListMap<>(comparator);
      skipListMap.put(key, key);
      bh.consume(skipListMap.get(distinctEqualCopy(key)));
      bh.consume(skipListMap);
    }
  }

  /**
   * Returns a distinct instance that's {@code .equals()} to {@code key} but never {@code ==} it, so
   * the lookup that follows can't take {@code HashMap}/{@code ConcurrentHashMap}'s internal {@code
   * key == storedKey || key.equals(storedKey)} identity fast path and skip calling {@code equals()}
   * -- which is exactly the dispatch this class exists to pollute. {@code Object}'s own {@code
   * equals()} is identity, so a decoy of that type has no distinct-but-equal instance to make; it's
   * returned as-is, and the identity fast path is then indistinguishable from a genuine {@code
   * equals()} call anyway.
   *
   * <p>{@code DONT_INLINE} makes this call boundary an optimization black box: without it, once a
   * caller like {@link #polluteHashDispatch} is inlined into a tight loop, the JIT can trace a
   * decoy key's concrete type straight back through this method to the literal it originated from
   * and devirtualize {@code hashCode()}/{@code equals()} via static type inference alone --
   * bypassing the shared, runtime type profile this class exists to pollute, no matter how many
   * distinct instances or types are pushed through it. Keeping this a real, non-inlined call forces
   * every caller to go through actual dispatch -- a type-erasing wormhole, the mirror image of
   * {@link Blackhole}'s value-erasing one.
   *
   * @param key the decoy key to copy
   * @return a distinct-but-equal copy, or {@code key} itself if it has no distinct-but-equal form
   */
  @CompilerControl(CompilerControl.Mode.DONT_INLINE)
  @SuppressWarnings(
      "deprecation") // boxed-type constructors: only way to force a non-cached instance
  private static Object distinctEqualCopy(Object key) {
    if (key instanceof String) {
      return new String((String) key);
    } else if (key instanceof Integer) {
      return new Integer((Integer) key);
    } else if (key instanceof Long) {
      return new Long((Long) key);
    } else if (key instanceof Double) {
      return new Double((Double) key);
    } else if (key instanceof Boolean) {
      return new Boolean((Boolean) key);
    } else {
      return key;
    }
  }
}

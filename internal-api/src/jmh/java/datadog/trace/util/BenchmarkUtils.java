package datadog.trace.util;

import java.util.Arrays;
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

/** Shared setup helpers for JMH benchmarks in this module. */
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
   * Call once from {@code @Setup(Level.Trial)}, passing the {@link Blackhole} JMH injects into the
   * setup method. Exercises {@link HashSet}/{@link java.util.HashMap}, the tracer's {@link
   * CollectionUtils#tryMakeImmutableSet} immutable sets, {@link ConcurrentHashMap}, and {@link
   * TreeMap}/{@link TreeSet}/{@link ConcurrentSkipListMap} with several distinct key classes, so
   * each structure's internal {@code hashCode()}/{@code equals()}/{@code compareTo()} dispatch -- a
   * call site shared JVM-wide by every instance of that structure in the process, regardless of
   * which specific instance or call site invokes {@code add}/{@code contains}/{@code get} -- is
   * already megamorphic before a benchmark measures lookups against a single key type.
   *
   * <p>This matches production: those shared internal call sites are hit by every hash- or
   * sorted-based structure in the JVM across whatever key types the whole application uses, so
   * they're realistically almost always megamorphic. An isolated benchmark that only ever looks up
   * one key type (e.g. {@code String}) would otherwise leave them artificially monomorphic for the
   * entire run, understating real dispatch cost.
   *
   * <p>Deliberately does not touch the benchmark's own {@code contains}/{@code add}/{@code get}
   * call sites -- those are realistically free to specialize per caller, the way a genuinely hot,
   * narrowly-typed call site would in production.
   *
   * <p>Not to be confused with the CHA-defeat decoys in {@code SingleThreadedMapBenchmark}/{@code
   * ThreadSafeMapBenchmark} ({@code KeyStrategy} implementors referenced only so they're loaded,
   * never invoked): that technique denies class-hierarchy analysis a single-implementor bet for a
   * narrow, dd-trace-java-owned interface, and works by class-loading alone. It doesn't apply here
   * -- {@code Object.hashCode()}/{@code equals()}/{@code compareTo()} already have countless
   * implementors loaded in any real JVM, so a single-implementor CHA bet was never available for
   * them. What gates their dispatch is the interpreter's per-call-site type profile, which only
   * invocation can pollute -- hence this helper actually calls {@code add}/{@code contains}/{@code
   * get}, rather than just loading classes.
   *
   * <p>{@code @Setup(Level.Trial)}, not {@code Level.Invocation}: the latter's cost is included in
   * every {@code Throughput}/{@code AverageTime} measurement's own timed window (JMH has no way to
   * subtract per-invocation setup cost without adding per-op {@code System.nanoTime()} overhead of
   * its own), so once pollution isn't free relative to the benchmarked op, it would dominate the
   * reported number instead of the thing being measured.
   *
   * <p>Every collection here is freshly allocated per pass and immediately consumed via {@code bh}
   * -- not just the {@code boolean}/lookup results, but the collection instances themselves.
   * Consuming only a lookup's result would leave the freshly-allocated, never-escaping collection
   * open to scalar replacement: once HotSpot compiles this loop as its own unit and proves a
   * just-allocated {@code HashSet} never escapes it, escape analysis can devirtualize the {@code
   * hashCode()}/{@code equals()} calls against that specific instance directly, bypassing the
   * shared, megamorphic call site entirely. Forcing every collection itself through the {@link
   * Blackhole} closes that loophole, so the allocations here don't need to be reused across calls
   * the way a measured hot path would.
   */
  public static void warmUpHashDispatch(Blackhole bh) {
    for (int i = 0; i < WARM_UP_ITERATIONS; ++i) {
      polluteHashDispatch(bh);
      polluteCompareToDispatch(bh);
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
   * Counterpart to {@link #polluteHashDispatch} for the {@code compareTo}-based dispatch used by
   * {@code TreeMap}/{@code TreeSet}/{@code ConcurrentSkipListMap}, instead of {@code hashCode()}/
   * {@code equals()}.
   *
   * <p>Unlike the hash-based structures above, a single sorted collection can't hold multiple decoy
   * key classes at once: natural ordering calls {@code key.compareTo(existing)}, which throws
   * {@code ClassCastException} the moment two mutually-incomparable types meet (e.g. a {@code
   * String} and an {@code Integer}). So each key type gets its own fresh collection here, one type
   * at a time. That's still enough to make the dispatch megamorphic: HotSpot's type profile lives
   * on the bytecode call site inside {@code TreeMap}/{@code TreeSet}/{@code
   * ConcurrentSkipListMap}'s shared implementation, not on any one collection instance, so driving
   * several receiver types through that site across several collection instances pollutes it
   * exactly as effectively as driving them through one shared instance would.
   *
   * <p>{@code Object}'s decoy is skipped: it isn't {@link Comparable}, so it has no natural
   * ordering to dispatch through in the first place -- the same reason it's exempt from an {@code
   * equals()}-identity copy in {@link #distinctEqualCopy}.
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
   * every caller to go through actual dispatch.
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

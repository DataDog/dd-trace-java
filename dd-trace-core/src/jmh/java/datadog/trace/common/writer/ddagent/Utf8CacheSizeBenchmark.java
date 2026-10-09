package datadog.trace.common.writer.ddagent;

import static datadog.trace.common.writer.ddagent.Utf8Workload.NUM_LOOKUPS;
import static datadog.trace.common.writer.ddagent.Utf8Workload.nextTag;
import static datadog.trace.common.writer.ddagent.Utf8Workload.nextValue;
import static java.nio.charset.StandardCharsets.UTF_8;

import datadog.communication.serialization.EncodingCache;
import datadog.communication.serialization.GenerationalUtf8Cache;
import datadog.communication.serialization.SimpleUtf8Cache;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Sweeps cache capacity for all three caches against the same {@link Utf8Workload}, since the fixed
 * sizes in {@link Utf8Benchmark} (128, 192) only show each cache at one operating point. The
 * capacities picked -- 10, 100, 1000 -- roughly bracket the range actually used in production (a
 * handful of near-static tag names up to {@link SimpleUtf8Cache#MAX_CAPACITY}), so this shows how
 * each design degrades as the workload's ~180-entry stable set plus unbounded "custom" churn
 * outgrows or comfortably fits the budget.
 *
 * <p>Best paired with {@code -prof gc}: the interesting signal is allocation (entries created,
 * bytes/op), not throughput -- see {@link Utf8Benchmark}'s note on why raw ops/s undersells the
 * caches relative to the intrinsified {@code getBytes} baseline.
 *
 * <p>{@code NOCACHE} is the uncached baseline (always calls {@code getBytes} directly) -- it
 * ignores {@code capacity}, so it's redundant across the three capacity values, but keeping it in
 * the same parameter sweep makes it trivial to eyeball against every row of the cached variants.
 *
 * <h2>Results (capacity sweep, {@code -prof gc}, normalized)</h2>
 *
 * <p>Throughput in ops/s, allocation in bytes/op, GC rate normalized to counts/sec ({@code
 * gc.count}/{@code gc.time} are cumulative sums over the whole run, so raw values aren't comparable
 * across runs with different fork counts without dividing by total measurement time).
 *
 * <pre>
 * capacity  kind          ops/s     alloc B/op  alloc vs. NOCACHE  alloc MB/s  GC/s   GC time %
 * 10        NOCACHE       2007.9    1,247,228   --                 2388.3     3.98   0.22%
 * 10        DDCACHE       1315.7    1,447,111   +16.0% (worse)      1815.7    3.03   0.17%
 * 10        SIMPLE        1401.6    1,305,537   +4.7%  (worse)      1745.1    2.91   0.16%
 * 10        GENERATIONAL  1205.6    1,318,938   +5.7%  (worse)      1516.4    2.53   0.15%
 * 100       NOCACHE       2003.6    1,247,238   --                 2383.1     3.96   0.22%
 * 100       DDCACHE       1397.4    1,248,407   +0.1%  (breakeven)  1663.7    2.77   0.16%
 * 100       SIMPLE        1451.3    1,161,541   -6.9%               1607.6    2.68   0.15%
 * 100       GENERATIONAL  1422.4    1,046,133   -16.1%              1419.0    2.36   0.13%
 * 1000      NOCACHE       1966.6    1,247,231   --                 2339.1     3.88   0.22%
 * 1000      DDCACHE       1648.9    1,034,356   -17.1%              1626.6    2.72   0.20%
 * 1000      SIMPLE        1641.3    974,316     -21.9%              1525.1    2.54   0.14%
 * 1000      GENERATIONAL  1709.5    961,062     -23.0%              1566.8    2.61   0.15%
 * </pre>
 *
 * <h2>Key findings</h2>
 *
 * <ul>
 *   <li>All three caches lose on raw throughput to the uncached baseline at every capacity tested.
 *       {@link java.lang.String#getBytes(java.nio.charset.Charset)} is intrinsified, so a cache
 *       lookup is strictly more expensive per-op than just re-encoding -- these caches only pay off
 *       on allocation, not throughput. Any cache meant to reduce app-thread allocation has to beat
 *       a TLAB bump-pointer allocation, which is a very cheap operation to beat.
 *   <li>Allocation reduction is real but smaller than naively expected -- at capacity 1000 all
 *       three caches reduce allocation by only ~17-23%, not the ~90% you'd guess from hit rate
 *       alone. Root cause: {@link Utf8Workload#nextStandardValue}/{@link
 *       Utf8Workload#nextCustomValue} build each value via {@code tag + int} string concatenation
 *       on <em>every</em> lookup, which allocates a fresh {@code String} regardless of whether the
 *       subsequent UTF-8 encoding is cached. For ASCII-compact strings, {@code getBytes(UTF_8)} is
 *       itself just a copy of the existing byte array, so a cache hit only saves that one array
 *       copy -- not the string-construction cost. This structurally caps the max possible
 *       allocation win for any UTF8 cache on this workload.
 *   <li>{@code DDCACHE} has a consistent allocation disadvantage vs. {@code SIMPLE}/{@code
 *       GENERATIONAL} (worst at capacity 10, where it's the only arm to allocate <em>more</em> than
 *       {@code NOCACHE}), from its generic {@code Pair.of(key, value)} wrapper allocated on every
 *       stored miss ({@code FixedSizeCache.produceAndStoreValue}). {@code SIMPLE}/{@code
 *       GENERATIONAL} avoid this on one-off values via a marker/bloom-style gate ({@code
 *       Caching.mark}) that defers the wrapper allocation to a value's <em>second</em> observed
 *       touch -- same immutable-wrapper-for-lock-freedom pattern as {@code DDCACHE}, but the
 *       wrapper cost is paid only for values that actually recur. The marker is advisory, not
 *       authoritative: a wrong mark only costs a redundant allocation on the next touch, it can
 *       never cause the cache to return incorrect data.
 *   <li>{@code SIMPLE} and {@code GENERATIONAL} are close to equal at capacity 100, with {@code
 *       GENERATIONAL} pulling ahead at capacity 1000 -- worth checking what capacity these caches
 *       actually run at in production before assuming {@code SIMPLE}'s extra complexity over {@code
 *       GENERATIONAL} is still earning its keep.
 * </ul>
 */
@BenchmarkMode(Mode.Throughput)
@State(Scope.Thread)
public class Utf8CacheSizeBenchmark {

  public enum CacheKind {
    NOCACHE,
    DDCACHE,
    SIMPLE,
    GENERATIONAL
  }

  @Param({"10", "100", "1000"})
  int capacity;

  @Param({"NOCACHE", "DDCACHE", "SIMPLE", "GENERATIONAL"})
  CacheKind cacheKind;

  EncodingCache cache;

  // Only Simple/Generational have anything to recalibrate; DDCache is a no-op here.
  Runnable recalibrate;

  @Setup(Level.Trial)
  public void setup() {
    switch (cacheKind) {
      case NOCACHE:
        cache = value -> value.toString().getBytes(UTF_8);
        recalibrate = () -> {};
        break;
      case DDCACHE:
        DDCache<String, byte[]> ddCache = DDCaches.newFixedSizeCache(capacity);
        cache = value -> ddCache.computeIfAbsent(value.toString(), v -> v.getBytes(UTF_8));
        recalibrate = () -> {};
        break;
      case SIMPLE:
        SimpleUtf8Cache simpleCache = new SimpleUtf8Cache(capacity);
        cache = simpleCache;
        recalibrate = simpleCache::recalibrate;
        break;
      case GENERATIONAL:
        GenerationalUtf8Cache generationalCache = new GenerationalUtf8Cache(capacity);
        cache = generationalCache;
        recalibrate = generationalCache::recalibrate;
        break;
    }
  }

  @Benchmark
  public void lookup(Blackhole bh) {
    // Single thread drives recalibrate inline, at a transaction boundary -- see Utf8Benchmark.
    recalibrate.run();

    for (int i = 0; i < NUM_LOOKUPS; ++i) {
      String tag = nextTag();
      String value = nextValue(tag);

      bh.consume(cache.encode(value));
    }
  }
}

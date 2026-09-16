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

package datadog.trace.common.metrics;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import datadog.trace.api.Pair;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.common.writer.ddagent.GenerationalUtf8Cache;
import datadog.trace.core.CoreSpan;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.Random;
import java.util.function.Function;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Benchmarks the peer tag encoding cache in {@link ConflatingMetricsAggregator#getPeerTags}.
 *
 * <p>Two cache designs are compared:
 *
 * <ol>
 *   <li><b>currentApproach</b> – current two-level DDCache: outer cache keyed by tag name, inner
 *       {@code DDCache<String, UTF8BytesString>} keyed by tag value. Returns a shared
 *       {@link UTF8BytesString} with zero allocations on cache hit.
 *   <li><b>generationalApproach</b> – replaces the inner DDCache with a {@link
 *       GenerationalUtf8Cache} keyed by the full composite {@code "tag:value"} string. Returns
 *       {@code byte[]} from the generational cache, then wraps in a fresh {@link UTF8BytesString}.
 * </ol>
 *
 * <p>Run with {@code -prof gc} to compare allocation rates in addition to throughput.
 */
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 5, timeUnit = SECONDS)
@Measurement(iterations = 5, time = 5, timeUnit = SECONDS)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(NANOSECONDS)
@Fork(value = 1)
@Threads(8)
public class PeerTagsCacheBenchmark {

  private static final String PEER_HOSTNAME_TAG = "peer.hostname";
  private static final Set<String> PEER_TAGS = Collections.singleton(PEER_HOSTNAME_TAG);
  public static final int PEER_TAGS_CAPACITY = 512;
  public static final int CREATOR_CAPACITY = 64;

  /**
   * Number of distinct {@code peer.hostname} values across the 64-span trace.
   *
   * <ul>
   *   <li>10 → low cardinality, heavy cache-hit workload
   *   <li>200 → moderate, fits in both caches
   *   <li>1000 → high cardinality, exceeds the 512-slot inner DDCache
   * </ul>
   */
  @Param({"10", "200", "500", "1000"})
  int distinctValues;

  /** Spans pre-generated once per benchmark run; cycled through with {@code idx}. */
  private SimpleSpan[] spans;

  /** Cycling index. Not {@code volatile} – single-threaded benchmark. */
  private int idx;

  private DDCache<String, Pair<DDCache<String, UTF8BytesString>, Function<String, UTF8BytesString>>>
      currentOuterCache;

  private Function<
          String, Pair<DDCache<String, UTF8BytesString>, Function<String, UTF8BytesString>>>
      currentCacheAdder;


  private DDCache<String, Pair<DDCache<String, UTF8BytesString>, Function<String, UTF8BytesString>>>
      generationalOuterCache;
  private GenerationalUtf8Cache peerTagsBytesCache;
  private Function<
          String, Pair<DDCache<String, UTF8BytesString>, Function<String, UTF8BytesString>>>
      generationalCacheAdder;

  @Setup
  public void setup() {
    int poolSize = Math.min(distinctValues, CREATOR_CAPACITY);
    String[] hostnames = new String[poolSize];
    if (distinctValues <= CREATOR_CAPACITY) {
      // Exact set: "hostname-0" … "hostname-N"
      for (int i = 0; i < poolSize; i++) {
        hostnames[i] = "hostname-" + i;
      }
    } else {
      Random rng = new Random(42);
      for (int i = 0; i < poolSize; i++) {
        hostnames[i] = "hostname-" + rng.nextInt(distinctValues);
      }
    }

    spans = new SimpleSpan[CREATOR_CAPACITY];
    for (int i = 0; i < spans.length; i++) {
      SimpleSpan span =
          new SimpleSpan("svc", "op", "resource", "web", true, true, false, 0L, 10L, 0);
      span.setTag("span.kind", "client");
      span.setTag(PEER_HOSTNAME_TAG, hostnames[i % poolSize]);
      spans[i] = span;
    }

    currentOuterCache = DDCaches.newFixedSizeCache(CREATOR_CAPACITY);
    currentCacheAdder =
        key ->
            Pair.of(
                DDCaches.newFixedSizeCache(PEER_TAGS_CAPACITY),
                (String value) -> UTF8BytesString.create(key + ":" + value));

    generationalOuterCache = DDCaches.newFixedSizeCache(CREATOR_CAPACITY);
    peerTagsBytesCache = new GenerationalUtf8Cache(PEER_TAGS_CAPACITY);
    generationalCacheAdder =
        key ->
            Pair.of(
                DDCaches.newFixedSizeCache(PEER_TAGS_CAPACITY),
                value -> {
                  final String composite = key + ":" + value;
                  return UTF8BytesString.create(composite, peerTagsBytesCache.getUtf8(composite));
                });
  }

  private List<UTF8BytesString> getPeerTagsCurrent(CoreSpan<?> span) {
    List<UTF8BytesString> peerTags = new ArrayList<>(PEER_TAGS.size());
    for (String peerTag : PEER_TAGS) {
      Object value = span.unsafeGetTag(peerTag);
      if (value != null) {
        final Pair<DDCache<String, UTF8BytesString>, Function<String, UTF8BytesString>>
            cacheAndCreator = currentOuterCache.computeIfAbsent(peerTag, currentCacheAdder);
        peerTags.add(
            cacheAndCreator
                .getLeft()
                .computeIfAbsent(value.toString(), cacheAndCreator.getRight()));
      }
    }
    return peerTags;
  }

  // Experiement
  private List<UTF8BytesString> getPeerTagsGenerational(CoreSpan<?> span) {
    List<UTF8BytesString> peerTags = new ArrayList<>(PEER_TAGS.size());
    for (String peerTag : PEER_TAGS) {
      Object value = span.unsafeGetTag(peerTag);
      if (value != null) {
        final Pair<DDCache<String, UTF8BytesString>, Function<String, UTF8BytesString>>
            cacheAndCreator = generationalOuterCache.computeIfAbsent(peerTag, generationalCacheAdder);
        peerTags.add(
            cacheAndCreator
                .getLeft()
                .computeIfAbsent(value.toString(), cacheAndCreator.getRight()));
      }
    }
    return peerTags;
  }

  @Benchmark
  public void currentApproach(Blackhole bh) {
    bh.consume(getPeerTagsCurrent(spans[idx & 63]));
    idx++;
  }

  @Benchmark
  public void generationalApproach(Blackhole bh) {
    bh.consume(getPeerTagsGenerational(spans[idx & 63]));
    idx++;
  }
}

package datadog.trace.common.writer.ddagent;

import static datadog.trace.common.writer.ddagent.Utf8Workload.NUM_LOOKUPS;
import static datadog.trace.common.writer.ddagent.Utf8Workload.nextTag;
import static datadog.trace.common.writer.ddagent.Utf8Workload.nextValue;

import datadog.communication.serialization.GenerationalUtf8Cache;
import datadog.communication.serialization.SimpleUtf8Cache;
import java.nio.charset.StandardCharsets;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Single-threaded UTF8 cache benchmark. This reflects how the caches are actually driven today:
 * trace serialization runs on a single thread, so one thread performs the lookups and drives {@link
 * GenerationalUtf8Cache#recalibrate()} inline at a natural transaction boundary (here, once per
 * op). This is the representative allocation/throughput number. See {@link Utf8ConcurrentBenchmark}
 * for the multi-threaded contract/guardrail variant.
 *
 * <p>This benchmark isn't really intended to measure throughput, but rather to be used with "-prof
 * gc" to check bytes / op. Since {@link String#getBytes(java.nio.charset.Charset)} is intrinsified
 * the caches typically perform worse throughput wise; the benefit of the caches is to reduce
 * allocation.
 */
@BenchmarkMode(Mode.Throughput)
public class Utf8Benchmark {
  @Benchmark
  public static final String tagUtf8_baseline() {
    return nextTag();
  }

  @Benchmark
  public static final byte[] tagUtf8_nocache() {
    String tag = nextTag();
    return tag.getBytes(StandardCharsets.UTF_8);
  }

  static final SimpleUtf8Cache TAG_CACHE = new SimpleUtf8Cache(128);

  @Benchmark
  public static final byte[] tagUtf8_w_cache() {
    String tag = nextTag();

    byte[] cache = TAG_CACHE.getUtf8(tag);
    if (cache != null) return cache;

    return tag.getBytes(StandardCharsets.UTF_8);
  }

  @Benchmark
  public static final void valueUtf8_baseline(Blackhole bh) {
    for (int i = 0; i < NUM_LOOKUPS; ++i) {
      String tag = nextTag();
      String value = nextValue(tag);

      bh.consume(tag);
      bh.consume(value);
    }
  }

  static final GenerationalUtf8Cache VALUE_CACHE = new GenerationalUtf8Cache(64, 128);

  @Benchmark
  public static final void valueUtf8_cache_generational(Blackhole bh) {
    GenerationalUtf8Cache valueCache = VALUE_CACHE;
    valueCache.recalibrate(); // single thread drives recalibrate inline, at a transaction boundary

    for (int i = 0; i < NUM_LOOKUPS; ++i) {
      String tag = nextTag();
      String value = nextValue(tag);

      byte[] lookup = valueCache.getUtf8(value);
      bh.consume(lookup);
    }
  }

  static final SimpleUtf8Cache SIMPLE_VALUE_CACHE = new SimpleUtf8Cache(128);

  @Benchmark
  public static final void valueUtf8_cache_simple(Blackhole bh) {
    SimpleUtf8Cache valueCache = SIMPLE_VALUE_CACHE;
    valueCache.recalibrate(); // single thread drives recalibrate inline, at a transaction boundary

    for (int i = 0; i < NUM_LOOKUPS; ++i) {
      String tag = nextTag();
      String value = nextValue(tag);

      byte[] lookup = valueCache.getUtf8(value);
      bh.consume(lookup);
    }
  }

  @Benchmark
  public static final void valueUtf8_nocache(Blackhole bh) {
    for (int i = 0; i < NUM_LOOKUPS; ++i) {
      String tag = nextTag();
      String value = nextValue(tag);

      bh.consume(tag);
      bh.consume(value.getBytes(StandardCharsets.UTF_8));
    }
  }
}

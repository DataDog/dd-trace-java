package datadog.trace.common.writer.ddagent;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadLocalRandom;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.infra.Blackhole;

/**
 * This benchmark isn't really intended to used to measure throughput, but rather to be used with
 * "-prof gc" to check bytes / op.
 *
 * <p>Since {@link String#getBytes(java.nio.charset.Charset)} is intrinsified the caches typically
 * perform worse throughput wise, the benefit of the caches is to reduce allocation. Intention of
 * this benchmark is to create data that roughly resembles what might be seen in a trace payload.
 * Tag names are quite static, tag values are mostly low cardinality, but some tag values have
 * infinite cardinality.
 */
@BenchmarkMode(Mode.Throughput)
public class Utf8Benchmark {
  static final int NUM_LOOKUPS = 10_000;

  static final String[] TAGS = {
    "_dd.asm.keep",
    "ci.provider",
    "language",
    "db.statement",
    "ci.job.url",
    "ci.pipeline.url",
    "db.pool",
    "http.forwarder",
    "db.warehouse",
    "custom"
  };

  static int pos = 0;
  static int standardVal = 0;

  static final String nextTag() {
    if (pos == TAGS.length - 1) {
      pos = 0;
    } else {
      pos += 1;
    }
    return TAGS[pos];
  }

  static final String nextValue(String tag) {
    if (tag.equals("custom")) {
      return nextCustomValue(tag);
    } else {
      return nextStandardValue(tag);
    }
  }

  /*
   * Produces a high cardinality value - > thousands of distinct values per tag - many 1-time values
   */
  static final String nextCustomValue(String tag) {
    return tag + ThreadLocalRandom.current().nextInt();
  }

  /*
   * Produces a moderate cardinality value - tens of distinct values per tag
   */
  static final String nextStandardValue(String tag) {
    return tag + ThreadLocalRandom.current().nextInt(20);
  }

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
    valueCache.recalibrate();

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
    valueCache.recalibrate();

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

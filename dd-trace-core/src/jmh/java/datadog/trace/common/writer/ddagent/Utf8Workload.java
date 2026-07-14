package datadog.trace.common.writer.ddagent;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Shared workload for the UTF8 cache benchmarks, so the single-threaded ({@link Utf8Benchmark}) and
 * multi-threaded ({@link Utf8ConcurrentBenchmark}) variants measure identical inputs and can't
 * drift apart.
 *
 * <p>Roughly resembles a trace payload: tag names are quite static, tag values are mostly low
 * cardinality, but some ("custom") have effectively infinite cardinality.
 */
final class Utf8Workload {
  private Utf8Workload() {}

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

  // Randomized rather than a shared counter so the concurrent variant stays thread-safe (a shared
  // ++ index races and can walk off the end of TAGS under multiple threads).
  static String nextTag() {
    return TAGS[ThreadLocalRandom.current().nextInt(TAGS.length)];
  }

  static String nextValue(String tag) {
    if (tag.equals("custom")) {
      return nextCustomValue(tag);
    } else {
      return nextStandardValue(tag);
    }
  }

  /** High cardinality - thousands of distinct values per tag, many one-time values. */
  static String nextCustomValue(String tag) {
    return tag + ThreadLocalRandom.current().nextInt();
  }

  /** Moderate cardinality - tens of distinct values per tag. */
  static String nextStandardValue(String tag) {
    return tag + ThreadLocalRandom.current().nextInt(20);
  }
}

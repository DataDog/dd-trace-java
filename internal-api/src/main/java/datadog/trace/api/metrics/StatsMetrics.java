package datadog.trace.api.metrics;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Telemetry counters for client-side trace-stats span collapses. Mirrors the statsd {@code
 * datadog.tracer.stats.collapsed_spans} metric so the same signal is visible over telemetry, which
 * (unlike statsd) is always wired regardless of whether a dogstatsd sink is configured -- the stats
 * aggregator's {@code HealthMetrics} is {@code NO_OP} when health metrics are disabled.
 *
 * <p>Each collapse "reason" (e.g. {@code collapsed:additional_metric_tags}, {@code
 * oversized:additional_metric_tags}, {@code collapsed:peer_tags}, {@code collapsed:<field>}, {@code
 * collapsed:whole_key}) is a distinct telemetry tag on a single {@code stats.collapsed_spans}
 * counter. The reason set is bounded and low-cardinality by construction (the cardinality limits
 * themselves guarantee it), so a dynamic map keyed by reason cannot itself blow up.
 *
 * <p>Counters are incremented from the single stats-aggregator thread and drained from the
 * telemetry thread: the backing map is a {@link ConcurrentMap} and each counter is an {@link
 * AtomicLong}, so neither side needs external synchronization. {@link
 * TaggedCounter#getValueAndReset()} is only called from the draining thread.
 */
public final class StatsMetrics {
  static final String COLLAPSED_SPANS = "stats.collapsed_spans";

  private static final StatsMetrics INSTANCE = new StatsMetrics();

  // reason tag (e.g. "collapsed:additional_metric_tags") -> counter. Created on first collapse for
  // that reason; the reason set is bounded, so this never grows unboundedly.
  private final ConcurrentMap<String, TaggedCounter> collapsedByReason = new ConcurrentHashMap<>();

  public static StatsMetrics getInstance() {
    return INSTANCE;
  }

  private StatsMetrics() {}

  /**
   * Records {@code count} spans collapsed under the given {@code reason} tag (e.g. {@code
   * collapsed:additional_metric_tags}). No-op for a non-positive count.
   */
  public void onCollapsedSpans(String reason, long count) {
    if (count <= 0) {
      return;
    }
    collapsedByReason
        .computeIfAbsent(reason, tag -> new TaggedCounter(COLLAPSED_SPANS, tag))
        .counter
        .addAndGet(count);
  }

  public Collection<TaggedCounter> getTaggedCounters() {
    return this.collapsedByReason.values();
  }

  /** A named, single-tag counter drained as a telemetry {@code count} metric. */
  public static final class TaggedCounter implements CoreCounter {
    private final String name;
    private final String tag;
    private final AtomicLong counter = new AtomicLong();
    private long previousCount;

    TaggedCounter(String name, String tag) {
      this.name = name;
      this.tag = tag;
    }

    @Override
    public String getName() {
      return this.name;
    }

    public String getTag() {
      return this.tag;
    }

    @Override
    public long getValue() {
      return this.counter.get();
    }

    @Override
    public long getValueAndReset() {
      long count = this.counter.get();
      long delta = count - this.previousCount;
      this.previousCount = count;
      return delta;
    }
  }
}

package datadog.trace.api.metrics;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

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
 * telemetry thread: the backing map is a {@link ConcurrentMap} and each {@link TaggedCounter} is
 * atomic, so neither side needs external synchronization. {@link TaggedCounter#getValueAndReset()}
 * is only called from the draining thread.
 */
public final class StatsMetrics {
  static final String COLLAPSED_SPANS = "stats.collapsed_spans";
  static final String COLLAPSED_WHOLE_KEY = "collapsed:whole_key";

  private static final StatsMetrics INSTANCE = new StatsMetrics();

  // reason tag (e.g. "collapsed:additional_metric_tags") -> counter. Created on first collapse for
  // that reason; the reason set is bounded, so this never grows unboundedly.
  private final ConcurrentMap<String, TaggedCounter> collapsedByReason = new ConcurrentHashMap<>();

  // The one reason counted per dropped span on the hot aggregator path (aggregate table at cap);
  // every other reason is batched once per reporting cycle. Pre-created and cached so the per-span
  // increment is a direct counter hit rather than a map lookup -- this matters precisely when a
  // cardinality explosion pins the table at cap and every arriving span is dropped, turning a cold
  // path hot. Still registered in the map above, so the telemetry drain sees it with the rest.
  private final TaggedCounter wholeKeyCollapses =
      this.collapsedByReason.computeIfAbsent(
          COLLAPSED_WHOLE_KEY, tag -> new TaggedCounter(COLLAPSED_SPANS, tag));

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
        .add(count);
  }

  /**
   * Records a single whole-key collapse: a span dropped because the aggregate table was at cap with
   * no entry to evict. Increments the pre-created {@link #COLLAPSED_WHOLE_KEY} counter directly,
   * keeping the per-dropped-span aggregator path off the reason map.
   */
  public void onWholeKeyCollapse() {
    this.wholeKeyCollapses.add(1);
  }

  public Collection<TaggedCounter> getTaggedCounters() {
    return this.collapsedByReason.values();
  }
}

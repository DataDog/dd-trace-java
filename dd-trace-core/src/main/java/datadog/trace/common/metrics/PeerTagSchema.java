package datadog.trace.common.metrics;

import static datadog.trace.api.DDTags.BASE_SERVICE;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.trace.api.Config;
import datadog.trace.api.metrics.StatsMetrics;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.core.monitor.HealthMetrics;
import java.util.Set;

/**
 * Parallel arrays of peer-tag names and their {@link TagCardinalityHandler}s, using matching
 * indexes.
 *
 * <p>Each schema stores peer-tag names and their cardinality handlers by index. Producers capture
 * span tag values into a {@code String[]} with the same ordering as {@link #names}. Consumers pass
 * the index and captured value to {@link #register(int, String)} to canonicalize it through the
 * per-tag cardinality handler.
 *
 * <p>Two schemas exist:
 *
 * <ul>
 *   <li>{@link #INTERNAL} -- a singleton with one entry for {@code base.service}, used for
 *       internal-kind spans where only the base service is aggregated.
 *   <li>A peer-aggregation schema built via {@link #of(Set, String)} for {@code client}/{@code
 *       producer}/{@code consumer} spans. {@link ClientStatsAggregator} caches the most recently
 *       built schema and reconciles it on the aggregator thread once per reporting cycle by
 *       comparing {@link #state} against {@link DDAgentFeaturesDiscovery#state()}.
 * </ul>
 *
 * <p>Cardinality blocks are counted inside each {@link TagCardinalityHandler} and flushed once per
 * cycle via {@code ClientStatsAggregator#resetCardinalityHandlers} -- to {@link HealthMetrics} as a
 * per-cycle count and to the shared {@link CardinalityLimitReporter}, which aggregates by tag name
 * and emits a rate-limited warn summary.
 *
 * <p>Each {@link SpanSnapshot} captures its own schema reference so producer and consumer agree on
 * the indexing even if the current schema is replaced between capture and consumption.
 *
 * <p><b>Thread-safety:</b> the aggregator thread is the only thread that mutates this schema,
 * including its {@link TagCardinalityHandler}s and {@link #state}. Producer threads may read {@link
 * #names} and {@link #handlers} because they are final and published through the volatile {@code
 * cachedPeerTagSchema} reference in {@link ClientStatsAggregator}.
 */
final class PeerTagSchema {

  /**
   * Sentinel {@link #state} for schemas that are never reconciled against feature discovery: the
   * {@link #INTERNAL} singleton and test-built schemas. A {@code null} state always mismatches a
   * real discovery hash, so a schema built with it would rebuild on first reconcile -- but neither
   * of these schemas takes that path.
   */
  static final String NO_STATE = null;

  /** Singleton schema for internal-kind spans -- only {@code base.service}. */
  static final PeerTagSchema INTERNAL = new PeerTagSchema(new String[] {BASE_SERVICE}, NO_STATE);

  // Health/telemetry statsD tag per the approved Cardinality Limits RFC (section 5): peer-tag
  // collapses are reported under the lowercased protobuf field name peer_tags, aggregated across
  // every configured peer tag rather than per individual tag name.
  private static final String[] COLLAPSED_STATSD_TAG = {"collapsed:peer_tags"};

  final String[] names;
  final TagCardinalityHandler[] handlers;

  /**
   * The {@code DDAgentFeaturesDiscovery.state()} hash this schema was built from. The aggregator
   * thread reads and updates this once per reporting cycle when reconciling against the latest
   * discovery; producer threads never touch it. Plain (non-volatile, non-final) because the
   * aggregator is the sole reader/writer. May be {@code null} before discovery has produced a
   * response.
   */
  String state;

  /** Builds a schema for the given peer-tag names. Order is determined by the {@link Set}. */
  static PeerTagSchema of(Set<String> names, String state) {
    return new PeerTagSchema(names.toArray(new String[0]), state);
  }

  PeerTagSchema(String[] names, String state) {
    this.names = names;
    this.state = state;
    this.handlers = new TagCardinalityHandler[names.length];
    for (int i = 0; i < names.length; i++) {
      this.handlers[i] =
          new TagCardinalityHandler(
              names[i],
              Config.get()
                  .getTraceStatsCardinalityLimit(
                      "peer_tags", MetricCardinalityLimits.PEER_TAG_VALUE),
              MetricCardinalityLimits.USE_BLOCKED_SENTINEL);
    }
  }

  /**
   * Whether this schema contains exactly the same tag names as {@code other}. Used during
   * reconciliation: if feature discovery has a new {@link DDAgentFeaturesDiscovery#state()} but the
   * peer-tag set is unchanged, the aggregator can reuse this schema and update {@link #state}
   * instead of rebuilding the handlers.
   */
  boolean hasSameTagsAs(Set<String> other) {
    if (this.names.length != other.size()) {
      return false;
    }
    for (String name : this.names) {
      if (!other.contains(name)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Canonicalizes the peer-tag value at slot {@code i}. Returns {@link UTF8BytesString#EMPTY} for
   * null inputs and the handler's {@code "<tag>:tracer_blocked_value"} sentinel when the per-tag
   * cardinality budget is exhausted.
   */
  UTF8BytesString register(int i, String value) {
    return handlers[i].register(value);
  }

  /**
   * Resets cardinality tracking for each peer tag and reports how many values were blocked since
   * the previous reset.
   *
   * <p>The counts are sent to {@link HealthMetrics} for the current reporting cycle. They are also
   * added to {@code reporter}, which groups them by tag name for a rate-limited warning. Grouping
   * counts by name ensures they are not lost when a schema rebuild replaces the handlers.
   *
   * <p>This method must be called only from the aggregator thread because the handlers are not
   * thread-safe.
   */
  void resetHandlers(HealthMetrics healthMetrics, CardinalityLimitReporter reporter) {
    long totalCollapsed = 0;
    for (int i = 0; i < handlers.length; i++) {
      long numBlocked = handlers[i].reset();
      if (numBlocked > 0) {
        // The human-facing reporter names the specific peer tag that triggered the block.
        reporter.record(names[i], numBlocked);
      }
      totalCollapsed += numBlocked;
    }
    // The health metric is reported at the peer_tags field granularity (not per tag name) per the
    // approved Cardinality Limits RFC.
    if (totalCollapsed > 0) {
      healthMetrics.onTagCardinalityBlocked(COLLAPSED_STATSD_TAG, totalCollapsed);
      StatsMetrics.getInstance().onCollapsedSpans(COLLAPSED_STATSD_TAG[0], totalCollapsed);
    }
  }

  int size() {
    return names.length;
  }

  String name(int i) {
    return names[i];
  }
}

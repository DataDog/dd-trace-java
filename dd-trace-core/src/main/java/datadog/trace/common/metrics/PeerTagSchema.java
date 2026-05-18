package datadog.trace.common.metrics;

import static datadog.trace.api.DDTags.BASE_SERVICE;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.core.monitor.HealthMetrics;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * cycle (with a warn log) in {@link #resetHandlers(HealthMetrics)}.
 *
 * <p>Each {@link SpanSnapshot} captures its own schema reference so producer and consumer agree on
 * the indexing even if the current schema is replaced between capture and consumption.
 *
 * <p><b>Thread-safety:</b> all mutable state ({@link TagCardinalityHandler}s, the warn-once set,
 * and {@link #state}) is exercised only on the aggregator thread. {@link #names} and {@link
 * #handlers} are final and safe to read from any thread; producer threads access them through the
 * volatile {@code cachedPeerTagSchema} reference in {@link ClientStatsAggregator}.
 */
final class PeerTagSchema {

  private static final Logger log = LoggerFactory.getLogger(PeerTagSchema.class);

  /**
   * Sentinel {@link #state} for schemas that are never reconciled against feature discovery: the
   * {@link #INTERNAL} singleton and test-built schemas. A {@code null} state always mismatches a
   * real discovery hash, so a schema built with it would rebuild on first reconcile -- but neither
   * of these schemas takes that path.
   */
  static final String NO_STATE = null;

  /** Singleton schema for internal-kind spans -- only {@code base.service}. */
  static final PeerTagSchema INTERNAL = new PeerTagSchema(new String[] {BASE_SERVICE}, NO_STATE);

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
                      "peer_tag", MetricCardinalityLimits.PEER_TAG_VALUE));
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
   * Resets every {@link TagCardinalityHandler}'s working set, flushes accumulated per-tag block
   * counts to {@link HealthMetrics}, and emits a warn log for each tag that hit its limit this
   * cycle. Must be called on the aggregator thread; handlers are not thread-safe.
   */
  void resetHandlers(HealthMetrics healthMetrics) {
    for (int i = 0; i < handlers.length; i++) {
      long blocked = handlers[i].reset();
      if (blocked > 0) {
        log.warn(
            "Cardinality limit reached for peer tag '{}'; further values are reported as"
                + " 'tracer_blocked_value' until the next reporting cycle",
            names[i]);
        healthMetrics.onTagCardinalityBlocked(handlers[i].statsDTag(), blocked);
      }
    }
  }

  int size() {
    return names.length;
  }

  String name(int i) {
    return names[i];
  }
}

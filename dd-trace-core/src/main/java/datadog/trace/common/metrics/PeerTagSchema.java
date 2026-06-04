package datadog.trace.common.metrics;

import static datadog.trace.api.DDTags.BASE_SERVICE;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.core.monitor.HealthMetrics;
import java.util.HashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parallel arrays of peer-tag names and their {@link TagCardinalityHandler}s, indexed in lockstep.
 *
 * <p>Replaces the previous {@code Map<String, TagCardinalityHandler>} lookup with positional array
 * access: the producer captures span tag values into a {@code String[]} parallel to {@link #names},
 * and the consumer calls {@link #register(int, String)} at the same index to canonicalize the value
 * through the per-tag cardinality handler.
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
 * <p>Cardinality blocks emit a one-shot warn log per reporting cycle per tag (tracked via {@link
 * #warnedCardinality}). Per-tag block counts live inside each {@link TagCardinalityHandler} and are
 * returned by {@link TagCardinalityHandler#reset()}, then flushed to {@link
 * HealthMetrics#onTagCardinalityBlocked(String, long)} in {@link
 * #resetCardinalityHandlers(HealthMetrics)}.
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

  /** Singleton schema for internal-kind spans -- only {@code base.service}. */
  static final PeerTagSchema INTERNAL = new PeerTagSchema(new String[] {BASE_SERVICE}, null);

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

  /**
   * Per-cycle warn-once gating. {@code Set.add(name)} returns true exactly the first time a tag
   * gets blocked this cycle, which is the only time we want to emit the warn log. Cleared by {@link
   * #resetCardinalityHandlers(HealthMetrics)}.
   */
  private final Set<String> warnedCardinality = new HashSet<>();

  /** Builds a schema for the given peer-tag names. Order is determined by the {@link Set}. */
  static PeerTagSchema of(Set<String> names, String state) {
    return new PeerTagSchema(names.toArray(new String[0]), state);
  }

  /** Test-only factory: takes names array directly to build a schema in a specific order. */
  static PeerTagSchema testSchema(String[] names) {
    return new PeerTagSchema(names, null);
  }

  private PeerTagSchema(String[] names, String state) {
    this.names = names;
    this.state = state;
    this.handlers = new TagCardinalityHandler[names.length];
    for (int i = 0; i < names.length; i++) {
      this.handlers[i] =
          new TagCardinalityHandler(
              names[i], MetricCardinalityLimits.PEER_TAG_VALUE, AggregateEntry.LIMITS_ENABLED);
    }
  }

  /**
   * Whether this schema's tag names exactly match {@code other}. Used by the aggregator's reconcile
   * path: when a feature discovery refresh changes {@link DDAgentFeaturesDiscovery#state()} but the
   * resulting set is unchanged, the aggregator can keep this schema (and its warm cardinality
   * handlers) and just update {@link #state} instead of rebuilding.
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
   * null inputs and the handler's {@code "<tag>:blocked_by_tracer"} sentinel when the per-tag
   * cardinality budget is exhausted. The handler counts blocks internally; emits a one-shot warn
   * log per cycle per tag via {@link #warnedCardinality}.
   */
  UTF8BytesString register(int i, String value) {
    TagCardinalityHandler handler = handlers[i];
    UTF8BytesString result = handler.register(value);
    if (handler.isBlockedResult(result) && warnedCardinality.add(names[i])) {
      log.warn(
          "Cardinality limit reached for peer tag '{}'; further values are reported as"
              + " 'blocked_by_tracer' until the next reporting cycle",
          names[i]);
    }
    return result;
  }

  /**
   * Resets every {@link TagCardinalityHandler}'s working set, flushes accumulated per-tag block
   * counts to {@link HealthMetrics}, and clears the per-cycle warn-once tracking. Must be called on
   * the aggregator thread; handlers are not thread-safe.
   */
  void resetCardinalityHandlers(HealthMetrics healthMetrics) {
    for (int i = 0; i < handlers.length; i++) {
      long blocked = handlers[i].reset();
      if (blocked > 0) {
        healthMetrics.onTagCardinalityBlocked(handlers[i].statsDTag(), blocked);
      }
    }
    warnedCardinality.clear();
  }

  int size() {
    return names.length;
  }

  String name(int i) {
    return names[i];
  }
}

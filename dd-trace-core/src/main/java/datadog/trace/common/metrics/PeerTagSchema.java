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
 *   <li>A peer-aggregation schema built via {@link #of(Set, String, HealthMetrics)} for {@code
 *       client}/{@code producer}/{@code consumer} spans. {@link ClientStatsAggregator} caches the
 *       most recently built schema and reconciles it on the aggregator thread once per reporting
 *       cycle by comparing {@link #state} against {@link DDAgentFeaturesDiscovery#state()}.
 * </ul>
 *
 * <p>Cardinality blocks emit a one-shot warn log per reporting cycle per tag (tracked via {@link
 * #warnedCardinality}) and accumulate a per-tag block counter (tracked via {@link #blockedCounts})
 * that is flushed to {@link HealthMetrics#onTagCardinalityBlocked(String, long)} once per affected
 * tag at cycle reset. All per-cycle state resets in {@link #resetCardinalityHandlers()}.
 *
 * <p>Each {@link SpanSnapshot} captures its own schema reference so producer and consumer agree on
 * the indexing even if the current schema is replaced between capture and consumption.
 *
 * <p><b>Thread-safety:</b> all mutable state ({@link TagCardinalityHandler}s, the warn-once set,
 * {@link #blockedCounts}, and {@link #state}) is exercised only on the aggregator thread. {@link
 * #names} and {@link #handlers} are final and safe to read from any thread; producer threads access
 * them through the volatile {@code cachedPeerTagSchema} reference in {@link ClientStatsAggregator}.
 */
final class PeerTagSchema {

  private static final Logger log = LoggerFactory.getLogger(PeerTagSchema.class);

  /** Singleton schema for internal-kind spans -- only {@code base.service}. */
  static final PeerTagSchema INTERNAL =
      // INTERNAL is never reconciled, so the state value is irrelevant.
      new PeerTagSchema(new String[] {BASE_SERVICE}, null, HealthMetrics.NO_OP, null);

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

  private final HealthMetrics healthMetrics;

  /**
   * Per-cycle warn-once gating. {@code Set.add(name)} returns true exactly the first time a tag
   * gets blocked this cycle, which is the only time we want to emit the warn log. Cleared by {@link
   * #resetCardinalityHandlers()}.
   */
  private final Set<String> warnedCardinality = new HashSet<>();

  /**
   * Per-tag block counter, indexed in lockstep with {@link #names}. Incremented on every blocked
   * value during the cycle; flushed to {@link HealthMetrics#onTagCardinalityBlocked(String, long)}
   * and zeroed in {@link #resetCardinalityHandlers()}. Single statsd call per affected tag per
   * cycle keeps a misconfigured high-cardinality tag from flooding the metrics pipe.
   */
  private final long[] blockedCounts;

  /** Builds a schema for the given peer-tag names. Order is determined by the {@link Set}. */
  static PeerTagSchema of(Set<String> names, String state, HealthMetrics healthMetrics) {
    return new PeerTagSchema(names.toArray(new String[0]), state, healthMetrics, null);
  }

  /**
   * Builds a replacement schema, donating {@link TagCardinalityHandler}s from {@code previous} for
   * any tag name that survives the rebuild. Carrying handlers forward preserves their warm
   * prior-cycle UTF8 caches so persisting peer tags don't re-allocate {@code UTF8BytesString}s for
   * values that were already canonicalized under the prior schema. Used by the aggregator's
   * reconcile path; the caller must first call {@link #flushBlockedCounts()} on the outgoing schema
   * so its accumulated block telemetry reaches {@link HealthMetrics} before discard.
   *
   * <p>Handlers are matched by tag name. New names in the rebuilt schema get fresh handlers; names
   * that were in the old schema but aren't in the new one are dropped along with the old schema.
   */
  static PeerTagSchema of(
      Set<String> names, String state, HealthMetrics healthMetrics, PeerTagSchema previous) {
    return new PeerTagSchema(names.toArray(new String[0]), state, healthMetrics, previous);
  }

  /**
   * Test-only factory that takes the names array directly so tests can build a schema in a specific
   * order without going through a {@link Set}. Uses {@link HealthMetrics#NO_OP} and a {@code null}
   * state; tests exercising the cardinality-handler reset path should use {@link #of(Set, String,
   * HealthMetrics)} instead.
   */
  static PeerTagSchema testSchema(String[] names) {
    return new PeerTagSchema(names, null, HealthMetrics.NO_OP, null);
  }

  private PeerTagSchema(
      String[] names, String state, HealthMetrics healthMetrics, PeerTagSchema previous) {
    this.names = names;
    this.state = state;
    this.healthMetrics = healthMetrics;
    this.handlers = new TagCardinalityHandler[names.length];
    this.blockedCounts = new long[names.length];
    for (int i = 0; i < names.length; i++) {
      TagCardinalityHandler donated = previous == null ? null : previous.handlerFor(names[i]);
      this.handlers[i] =
          donated != null
              ? donated
              : new TagCardinalityHandler(names[i], MetricCardinalityLimits.PEER_TAG_VALUE);
    }
  }

  /**
   * Returns the {@link TagCardinalityHandler} for {@code name}, or {@code null} if this schema
   * doesn't carry that tag. Used only by the donor constructor above; not on any hot path.
   */
  private TagCardinalityHandler handlerFor(String name) {
    for (int i = 0; i < names.length; i++) {
      if (names[i].equals(name)) {
        return handlers[i];
      }
    }
    return null;
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
   * cardinality budget is exhausted. Increments the per-tag block counter on every block and emits
   * a one-shot warn log per cycle per tag; the counter is flushed to {@link HealthMetrics} in
   * {@link #resetCardinalityHandlers()}.
   */
  UTF8BytesString register(int i, String value) {
    TagCardinalityHandler handler = handlers[i];
    UTF8BytesString result = handler.register(value);
    if (handler.isBlockedResult(result)) {
      blockedCounts[i]++;
      String name = names[i];
      if (warnedCardinality.add(name)) {
        log.warn(
            "Cardinality limit reached for peer tag '{}'; further values are reported as"
                + " 'blocked_by_tracer' until the next reporting cycle",
            name);
      }
    }
    return result;
  }

  /**
   * Resets every {@link TagCardinalityHandler}'s working set and flushes the per-cycle telemetry
   * via {@link #flushBlockedCounts()}. Must be called on the aggregator thread; handlers are not
   * thread-safe.
   */
  void resetCardinalityHandlers() {
    for (TagCardinalityHandler handler : handlers) {
      handler.reset();
    }
    flushBlockedCounts();
  }

  /**
   * Flushes accumulated per-tag block counts to {@link HealthMetrics} and clears the per-cycle
   * warn-once tracking, without rotating the cardinality handlers' current/prior tables. Used by
   * the aggregator's reconcile path: when a tag-set change forces the schema to be replaced, the
   * outgoing schema's block telemetry must still reach {@code HealthMetrics}, but the per-tag
   * handlers themselves are transferred into the replacement schema (see the donor overload of
   * {@link #of(Set, String, HealthMetrics, PeerTagSchema)}) so their warm prior-cycle caches
   * survive the rebuild.
   */
  void flushBlockedCounts() {
    for (int i = 0; i < blockedCounts.length; i++) {
      if (blockedCounts[i] > 0) {
        healthMetrics.onTagCardinalityBlocked(names[i], blockedCounts[i]);
        blockedCounts[i] = 0;
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

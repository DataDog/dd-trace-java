package datadog.trace.common.metrics;

import static datadog.trace.api.DDTags.BASE_SERVICE;

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
 * and the consumer calls {@link #register(int, String)} at the same index to canonicalize the
 * value through the per-tag cardinality handler.
 *
 * <p>Two schemas exist:
 *
 * <ul>
 *   <li>{@link #INTERNAL} -- a singleton with one entry for {@code base.service}, used for
 *       internal-kind spans where only the base service is aggregated.
 *   <li>A peer-aggregation schema built via {@link #of(Set, long, HealthMetrics)} for {@code
 *       client}/{@code producer}/{@code consumer} spans. {@link ClientStatsAggregator} caches the
 *       most recently built schema and compares its {@link #peerTagsRevision} against {@code
 *       DDAgentFeaturesDiscovery.peerTagsRevision()} to decide when to rebuild.
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
 * <p><b>Thread-safety:</b> {@link TagCardinalityHandler}s and the warn-once set are not
 * thread-safe and must only be exercised on the aggregator thread. {@link #names} and {@link
 * #peerTagsRevision} are final and safe to read from any thread.
 */
final class PeerTagSchema {

  private static final Logger log = LoggerFactory.getLogger(PeerTagSchema.class);

  /** Sentinel revision for {@link #INTERNAL} -- it never changes. */
  static final long INTERNAL_REVISION = -1L;

  /** Singleton schema for internal-kind spans -- only {@code base.service}. */
  static final PeerTagSchema INTERNAL =
      new PeerTagSchema(new String[] {BASE_SERVICE}, INTERNAL_REVISION, HealthMetrics.NO_OP);

  final String[] names;
  final TagCardinalityHandler[] handlers;

  /**
   * The {@code DDAgentFeaturesDiscovery.peerTagsRevision()} value this schema was built from. Cache
   * callers ({@link ClientStatsAggregator}) compare this against the current revision to decide
   * whether to rebuild -- one final long carries the cache key on the schema itself.
   */
  final long peerTagsRevision;

  private final HealthMetrics healthMetrics;

  /**
   * Per-cycle warn-once gating. {@code Set.add(name)} returns true exactly the first time a tag
   * gets blocked this cycle, which is the only time we want to emit the warn log. Cleared by
   * {@link #resetCardinalityHandlers()}.
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
  static PeerTagSchema of(Set<String> names, long peerTagsRevision, HealthMetrics healthMetrics) {
    return new PeerTagSchema(names.toArray(new String[0]), peerTagsRevision, healthMetrics);
  }

  private PeerTagSchema(String[] names, long peerTagsRevision, HealthMetrics healthMetrics) {
    this.names = names;
    this.peerTagsRevision = peerTagsRevision;
    this.healthMetrics = healthMetrics;
    this.handlers = new TagCardinalityHandler[names.length];
    this.blockedCounts = new long[names.length];
    for (int i = 0; i < names.length; i++) {
      this.handlers[i] =
          new TagCardinalityHandler(names[i], MetricCardinalityLimits.PEER_TAG_VALUE);
    }
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
   * Resets every {@link TagCardinalityHandler}'s working set, flushes accumulated per-tag block
   * counts to {@link HealthMetrics}, and clears the per-cycle warn-once tracking. Must be called
   * on the aggregator thread; handlers are not thread-safe.
   */
  void resetCardinalityHandlers() {
    for (int i = 0; i < handlers.length; i++) {
      handlers[i].reset();
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

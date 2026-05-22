package datadog.trace.common.metrics;

import static datadog.trace.api.DDTags.BASE_SERVICE;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import java.util.Set;

/**
 * Names of the peer-tags eligible for client-stats aggregation, packed into a flat {@code String[]}
 * for parallel-array access by producers and the aggregator thread.
 *
 * <p>This is the minimal carrier shape used by {@link SpanSnapshot}: the producer captures per-span
 * values into a {@code String[]} parallel to {@link #names}, and the aggregator reconstructs the
 * encoded {@code tag:value} pairs from the same name index. It replaces the prior "flat pairs"
 * {@code [name0, value0, name1, value1, ...]} layout, which forced a worst-case allocation +
 * trim-and-copy on every span.
 *
 * <p>Two schemas exist:
 *
 * <ul>
 *   <li>{@link #INTERNAL} -- a singleton with one entry for {@code base.service}, used for
 *       internal-kind spans where only the base service is aggregated.
 *   <li>A peer-aggregation schema built via {@link #of(Set, long)} for {@code client}/{@code
 *       producer}/{@code consumer} spans. {@link ConflatingMetricsAggregator} caches the most
 *       recently built schema and reconciles it on the aggregator thread once per reporting cycle
 *       by comparing {@link #lastTimeDiscovered} against {@link
 *       DDAgentFeaturesDiscovery#getLastTimeDiscovered()}.
 * </ul>
 *
 * <p>This class deliberately has no cardinality limiters -- callers that need those layer them on
 * top.
 *
 * <p><b>Thread-safety:</b> {@link #names} is final and safe to read from any thread. {@link
 * #lastTimeDiscovered} is exercised only on the aggregator thread (read and updated in
 * reconciliation); producer threads access the schema only through the volatile {@code
 * cachedPeerTagSchema} reference in {@link ConflatingMetricsAggregator}.
 */
final class PeerTagSchema {

  /** Singleton schema for internal-kind spans -- only {@code base.service}. */
  static final PeerTagSchema INTERNAL =
      // -1L sentinel; INTERNAL is never reconciled, so the value just has to be distinct from any
      // real System.currentTimeMillis() that the aggregator might observe.
      new PeerTagSchema(new String[] {BASE_SERVICE}, -1L);

  final String[] names;

  /**
   * The {@code DDAgentFeaturesDiscovery.getLastTimeDiscovered()} value this schema was built from.
   * The aggregator thread reads and updates this once per reporting cycle when reconciling against
   * the latest discovery; producer threads never touch it. Plain (non-volatile, non-final) because
   * the aggregator is the sole reader/writer.
   */
  long lastTimeDiscovered;

  private PeerTagSchema(String[] names, long lastTimeDiscovered) {
    this.names = names;
    this.lastTimeDiscovered = lastTimeDiscovered;
  }

  /** Builds a schema for the given peer-tag names. Order is determined by the {@link Set}. */
  static PeerTagSchema of(Set<String> tags, long lastTimeDiscovered) {
    return new PeerTagSchema(tags.toArray(new String[0]), lastTimeDiscovered);
  }

  /**
   * Test-only factory that takes the names array directly so tests can build a schema in a specific
   * order without going through a {@link Set}.
   */
  static PeerTagSchema testSchema(String[] names) {
    return new PeerTagSchema(names, 0L);
  }

  /**
   * Whether this schema's tag names exactly match {@code other}. Used by the aggregator's reconcile
   * path: when a feature discovery refresh bumps {@link
   * DDAgentFeaturesDiscovery#getLastTimeDiscovered()} but the resulting set is unchanged, the
   * aggregator can keep this schema and just bump {@link #lastTimeDiscovered} instead of
   * rebuilding.
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

  int size() {
    return names.length;
  }
}

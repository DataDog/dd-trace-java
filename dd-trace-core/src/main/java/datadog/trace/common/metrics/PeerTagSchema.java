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
 *   <li>A peer-aggregation schema built via {@link #of(Set, String)} for {@code client}/{@code
 *       producer}/{@code consumer} spans. {@link ConflatingMetricsAggregator} caches the most
 *       recently built schema and reconciles it on the aggregator thread once per reporting cycle
 *       by comparing {@link #state} against {@link DDAgentFeaturesDiscovery#state()}.
 * </ul>
 *
 * <p>This class deliberately has no cardinality limiters -- callers that need those layer them on
 * top.
 *
 * <p><b>Thread-safety:</b> {@link #names} is final and safe to read from any thread. {@link #state}
 * is exercised only on the aggregator thread (read and updated in reconciliation); producer threads
 * access the schema only through the volatile {@code cachedPeerTagSchema} reference in {@link
 * ConflatingMetricsAggregator}.
 */
final class PeerTagSchema {

  /** Singleton schema for internal-kind spans -- only {@code base.service}. */
  static final PeerTagSchema INTERNAL =
      // INTERNAL is never reconciled, so the state value is irrelevant.
      new PeerTagSchema(new String[] {BASE_SERVICE}, null);

  final String[] names;

  /**
   * Precomputed {@code Arrays.hashCode(names)}. The schema is shared across many publishes so
   * recomputing it on the aggregator hot path (per-publish call to {@code AggregateEntry.hashOf})
   * was waste -- it showed up as a top aggregator-thread sample. Cached here, computed once at
   * construction.
   */
  final int namesHash;

  /**
   * The {@code DDAgentFeaturesDiscovery.state()} hash this schema was built from. The aggregator
   * thread reads and updates this once per reporting cycle when reconciling against the latest
   * discovery; producer threads never touch it. Plain (non-volatile, non-final) because the
   * aggregator is the sole reader/writer. May be {@code null} before discovery has produced a
   * response.
   */
  String state;

  private PeerTagSchema(String[] names, String state) {
    this.names = names;
    this.namesHash = java.util.Arrays.hashCode(names);
    this.state = state;
  }

  /** Builds a schema for the given peer-tag names. Order is determined by the {@link Set}. */
  static PeerTagSchema of(Set<String> tags, String state) {
    return new PeerTagSchema(tags.toArray(new String[0]), state);
  }

  /**
   * Test-only factory that takes the names array directly so tests can build a schema in a specific
   * order without going through a {@link Set}.
   */
  static PeerTagSchema testSchema(String[] names) {
    return new PeerTagSchema(names, null);
  }

  /**
   * Whether this schema's tag names exactly match {@code other}. Used by the aggregator's reconcile
   * path: when a feature discovery refresh changes {@link DDAgentFeaturesDiscovery#state()} but the
   * resulting set is unchanged, the aggregator can keep this schema and just update {@link #state}
   * instead of rebuilding.
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

package datadog.trace.common.metrics;

import static datadog.trace.api.DDTags.BASE_SERVICE;

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
 *   <li>A peer-aggregation schema built via {@link #of(Set)} for {@code client}/{@code
 *       producer}/{@code consumer} spans, cached on {@link ConflatingMetricsAggregator} keyed by
 *       reference equality of {@code DDAgentFeaturesDiscovery.peerTags()}.
 * </ul>
 *
 * <p>This class deliberately has no cardinality limiters or per-cycle state -- callers that need
 * those layer them on top.
 */
final class PeerTagSchema {

  /** Singleton schema for internal-kind spans -- only {@code base.service}. */
  static final PeerTagSchema INTERNAL = new PeerTagSchema(new String[] {BASE_SERVICE});

  final String[] names;

  private PeerTagSchema(String[] names) {
    this.names = names;
  }

  /** Builds a schema for the given peer-tag names. Order is determined by the {@link Set}. */
  static PeerTagSchema of(Set<String> tags) {
    return new PeerTagSchema(tags.toArray(new String[0]));
  }

  int size() {
    return names.length;
  }
}

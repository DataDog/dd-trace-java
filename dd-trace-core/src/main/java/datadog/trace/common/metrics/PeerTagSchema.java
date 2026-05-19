package datadog.trace.common.metrics;

import static datadog.trace.api.DDTags.BASE_SERVICE;

import java.util.Set;

/**
 * Parallel arrays of peer-tag names and their {@link TagCardinalityHandler}s, indexed in lockstep.
 *
 * <p>Replaces the previous {@code Map<String, TagCardinalityHandler>} lookup with positional array
 * access: the producer captures span tag values into a {@code String[]} parallel to {@link #names},
 * and the consumer applies {@link #handler(int)} at the same index to canonicalize.
 *
 * <p>Two schemas exist:
 *
 * <ul>
 *   <li>{@link #INTERNAL} -- a singleton with one entry for {@code base.service}, used for
 *       internal-kind spans where only the base service is aggregated.
 *   <li>A peer-aggregation schema built via {@link #of(Set)} for {@code client}/{@code
 *       producer}/{@code consumer} spans. Its lifecycle (including caching and rebuild on peer-tag
 *       config change) is owned by {@link ClientStatsAggregator}; this class is just the data
 *       holder.
 * </ul>
 *
 * <p>Each {@link SpanSnapshot} captures its own schema reference so producer and consumer agree on
 * the indexing even if the current schema is replaced between capture and consumption.
 *
 * <p><b>Thread-safety:</b> {@link TagCardinalityHandler}s are not thread-safe and must only be
 * exercised on the aggregator thread. {@link #names} is final and safe to read from any thread.
 */
final class PeerTagSchema {

  private static final int VALUE_LIMIT_PER_TAG = 512;

  /** Singleton schema for internal-kind spans -- only {@code base.service}. */
  static final PeerTagSchema INTERNAL = new PeerTagSchema(new String[] {BASE_SERVICE});

  final String[] names;
  final TagCardinalityHandler[] handlers;

  /** Builds a schema for the given peer-tag names. Order is determined by the {@link Set}. */
  static PeerTagSchema of(Set<String> names) {
    return new PeerTagSchema(names.toArray(new String[0]));
  }

  private PeerTagSchema(String[] names) {
    this.names = names;
    this.handlers = new TagCardinalityHandler[names.length];
    for (int i = 0; i < names.length; i++) {
      this.handlers[i] = new TagCardinalityHandler(names[i], VALUE_LIMIT_PER_TAG);
    }
  }

  /**
   * Resets every {@link TagCardinalityHandler}'s working set. Must be called on the aggregator
   * thread; handlers are not thread-safe.
   */
  void resetCardinalityHandlers() {
    for (TagCardinalityHandler h : handlers) {
      h.reset();
    }
  }

  int size() {
    return names.length;
  }

  String name(int i) {
    return names[i];
  }

  TagCardinalityHandler handler(int i) {
    return handlers[i];
  }
}

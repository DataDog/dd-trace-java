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
 *   <li>{@link #INTERNAL} — a singleton with one entry for {@code base.service}, used for
 *       internal-kind spans where only the base service is aggregated.
 *   <li>{@link #current()} — the schema for {@code client}/{@code producer}/{@code consumer} spans,
 *       refreshed lazily when {@code DDAgentFeaturesDiscovery.peerTags()} changes via {@link
 *       #currentSyncedTo(Set)}.
 * </ul>
 *
 * <p>Each {@link SpanSnapshot} captures its own schema reference so producer and consumer agree on
 * the indexing even if the current schema is replaced between capture and consumption.
 *
 * <p><b>Thread-safety:</b> {@link #currentSyncedTo} may be called from producer threads;
 * replacement of the volatile {@code CURRENT} reference is guarded by a lock. The {@link
 * TagCardinalityHandler}s themselves are not thread-safe and must only be exercised on the
 * aggregator thread (this is where the snapshot's schema is consumed).
 */
final class PeerTagSchema {

  private static final int VALUE_LIMIT_PER_TAG = 512;

  /** Singleton schema for internal-kind spans -- only {@code base.service}. */
  static final PeerTagSchema INTERNAL = new PeerTagSchema(new String[] {BASE_SERVICE});

  /** Current schema for peer-aggregation kinds; replaced atomically when peer tag names change. */
  private static volatile PeerTagSchema CURRENT = new PeerTagSchema(new String[0]);

  /**
   * Identity cache of the most recently observed {@code features.peerTags()} {@link Set} instance.
   * The producer hot path checks this first and skips the {@code names}-vs-set comparison when the
   * caller's set instance hasn't changed. In production this is the common case --
   * {@code DDAgentFeaturesDiscovery} returns the same Set instance until reconfiguration.
   */
  private static volatile Set<String> LAST_SYNCED_INPUT;

  final String[] names;
  final TagCardinalityHandler[] handlers;

  private PeerTagSchema(String[] names) {
    this.names = names;
    this.handlers = new TagCardinalityHandler[names.length];
    for (int i = 0; i < names.length; i++) {
      this.handlers[i] = new TagCardinalityHandler(names[i], VALUE_LIMIT_PER_TAG);
    }
  }

  /**
   * Returns the current peer-aggregation schema, lazily refreshing it if the supplied {@code
   * peerTagNames} differ from the cached set. Designed to be called from the producer hot path: the
   * common case is a single volatile read and an array-length / set-contains comparison.
   */
  static PeerTagSchema currentSyncedTo(Set<String> peerTagNames) {
    // Fast path: same Set instance as the last sync -> the cached schema is still valid, no
    // matches() loop needed. In production this is the steady-state case.
    if (peerTagNames == LAST_SYNCED_INPUT) {
      return CURRENT;
    }
    PeerTagSchema cur = CURRENT;
    if (matches(cur.names, peerTagNames)) {
      LAST_SYNCED_INPUT = peerTagNames;
      return cur;
    }
    synchronized (PeerTagSchema.class) {
      cur = CURRENT;
      if (!matches(cur.names, peerTagNames)) {
        cur = new PeerTagSchema(peerTagNames.toArray(new String[0]));
        CURRENT = cur;
      }
      LAST_SYNCED_INPUT = peerTagNames;
      return cur;
    }
  }

  /** Resets the working sets of {@link #INTERNAL} and {@link #current()}. */
  static void resetAll() {
    PeerTagSchema cur = CURRENT;
    for (TagCardinalityHandler h : cur.handlers) {
      h.reset();
    }
    for (TagCardinalityHandler h : INTERNAL.handlers) {
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

  private static boolean matches(String[] cur, Set<String> set) {
    if (cur.length != set.size()) {
      return false;
    }
    for (String n : cur) {
      if (!set.contains(n)) {
        return false;
      }
    }
    return true;
  }
}

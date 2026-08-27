package datadog.trace.common.metrics;

import datadog.trace.core.monitor.HealthMetrics;
import datadog.trace.util.Hashtable;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The {@link AggregateEntry} store of the consuming aggregator thread, keyed on the canonical
 * UTF8-encoded labels of a {@link SpanSnapshot}.
 *
 * <p>{@link #findOrInsert} canonicalizes the snapshot's fields through the cardinality handlers (so
 * cardinality-blocked values share a sentinel and collapse into one entry) and then computes the
 * lookup hash from that canonical form. Canonicalization runs into a reusable {@link
 * AggregateEntry.Canonical} scratch buffer; on a hit nothing is allocated, on a miss the buffer's
 * references are copied into a fresh entry and the buffer is overwritten on the next call.
 *
 * <p><b>Not thread-safe.</b> The aggregator thread is the sole writer of both this table and its
 * contained {@link AggregateEntry} state. Any cross-thread request that needs to mutate -- e.g.
 * {@link ClientStatsAggregator#disable()} -- must funnel onto the aggregator thread via the inbox
 * (see the {@code ClearSignal} routing in {@link Aggregator}). The invariant is convention-
 * enforced; nothing here checks the calling thread at runtime, so a wrong-thread call would corrupt
 * bucket chains silently.
 */
final class AggregateTable {

  /**
   * Stale means "not used in this reporting cycle". Held as a {@code static final} so it is a
   * non-capturing singleton rather than a fresh lambda per eviction.
   */
  private static final Predicate<AggregateEntry> STALE = AggregateEntry::isStale;

  private final Hashtable.State<AggregateEntry> state;

  private final AggregateEntry.Canonical canonical;

  AggregateTable(int maxAggregates) {
    this(maxAggregates, AdditionalTagsSchema.EMPTY);
  }

  AggregateTable(int maxAggregates, AdditionalTagsSchema additionalTagsSchema) {
    this(maxAggregates, new CoreHandlers(), additionalTagsSchema);
  }

  AggregateTable(
      int maxAggregates, CoreHandlers handlers, AdditionalTagsSchema additionalTagsSchema) {
    this.state = Hashtable.createCapped(maxAggregates);
    this.canonical = new AggregateEntry.Canonical(handlers, additionalTagsSchema);
  }

  void resetCoreHandlers(HealthMetrics healthMetrics, CardinalityLimitReporter reporter) {
    canonical.handlers.reset(healthMetrics, reporter);
  }

  /**
   * Live aggregate count. Exact from this class's point of view: {@link Hashtable#estimateSize} is
   * an estimate only across a reservation window, and {@link #findOrInsert} reserves and links
   * without yielding, so no caller can observe one.
   */
  int size() {
    return Hashtable.estimateSize(state);
  }

  boolean isEmpty() {
    return Hashtable.isLikelyEmpty(state);
  }

  /**
   * Returns the {@link AggregateEntry} to update for {@code snapshot}, lazily creating one on miss.
   * Returns {@code null} when the table is at capacity and no stale entry can be evicted -- the
   * caller should drop the data point in that case (reported via {@code
   * onStatsAggregateDropped}). Dropping the new key rather than evicting an established one is
   * deliberate: the cap is sized to the steady-state working set, so a full table of entries that
   * were all used this cycle means the new key is the outlier.
   *
   * <p>Cardinality limiting (see {@link MetricCardinalityLimits#USE_BLOCKED_SENTINEL}) reduces how
   * often eviction fires but doesn't eliminate it. Over-cap values for a single field collapse into
   * the shared {@code tracer_blocked_value} sentinel, so no one field can fill the table on its
   * own. But distinct in-budget combinations across fields (resource x service x operation x ...)
   * can still drive the entry count to {@code maxAggregates}, so eviction remains the backstop.
   *
   * <p>The scan that finds a stale entry, and its resume-where-it-left-off amortization, live in
   * {@link Hashtable#tryReserveOrEvict} -- this class only supplies {@link #STALE}.
   */
  AggregateEntry findOrInsert(SpanSnapshot snapshot) {
    canonical.populateFrom(snapshot);
    long keyHash = canonical.keyHash;
    for (AggregateEntry candidate = Hashtable.bucketFor(state, keyHash);
        candidate != null;
        candidate = candidate.next()) {
      if (candidate.keyHash == keyHash && canonical.matches(candidate)) {
        return candidate;
      }
    }
    // Miss path. Reserve before building the entry so a refused insert costs no allocation; the
    // reservation evicts a stale entry to make room if the table is already full.
    if (!Hashtable.tryReserveOrEvict(state, STALE)) {
      return null;
    }
    AggregateEntry entry = canonical.createEntry();
    Hashtable.insertReserved(state, keyHash, entry);
    return entry;
  }

  void forEach(Consumer<AggregateEntry> consumer) {
    Hashtable.forEach(state, consumer);
  }

  /**
   * Context-passing forEach. Useful for callers that want to avoid a capturing-lambda allocation on
   * each invocation -- pass a non-capturing {@link BiConsumer} (typically a {@code static final})
   * plus whatever side-band state it needs as {@code context}.
   */
  <C> void forEach(C context, BiConsumer<C, AggregateEntry> consumer) {
    Hashtable.forEach(state, context, consumer);
  }

  /** Removes entries whose {@code getHitCount() == 0}. */
  void expungeStaleAggregates() {
    Hashtable.evictAll(state, STALE);
  }

  void clear() {
    Hashtable.clear(state);
  }
}

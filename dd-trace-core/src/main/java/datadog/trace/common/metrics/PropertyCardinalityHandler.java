package datadog.trace.common.metrics;

import datadog.trace.api.internal.VisibleForTesting;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.util.Arrays;

/**
 * Cardinality-capped UTF8 encoder and cache for one aggregate-key field ({@code value} &rarr;
 * {@code UTF8(value)}).
 *
 * <p>Each reporting cycle (interval between client-stats flushes) has its own cardinality budget.
 * Once the budget is exhausted, new values get the {@code tracer_blocked_value} sentinel (or a
 * fresh allocation when sentinel mode is disabled). A prior-cycle table preserves {@link
 * UTF8BytesString} instances across the reset, so stable workloads pay zero allocations after the
 * first cycle.
 *
 * <p><b>Dual role -- limiter and cache.</b> Prior versions ran a per-field {@code DDCache} for UTF8
 * reuse with a separate global cardinality cap on top. Under high load that wasn't enough to stave
 * off long GC cycles: every miss still concatenated / UTF8-encoded the value before the cache could
 * store it. A cardinality limiter and a recent-value cache are both <em>sets of recently used
 * values</em>, so this class collapses them into one structure. Cardinality limiting happens first,
 * which lets the blocked path skip the concatenation and encoding entirely.
 *
 * <p>A pure limiter would fully reset each reporting cycle and destroy the cache. To preserve UTF8
 * reuse across resets, the handler keeps the previous cycle's entries verbatim in a parallel table
 * and reuses any matching {@link UTF8BytesString} when a value first appears in the new cycle.
 */
final class PropertyCardinalityHandler {
  // Upper bound prevents int overflow in the (cardinalityLimit * 2 - 1) capacity calculation.
  // Practical limits are 8..512; this cap is well beyond any realistic configuration.
  private static final int MAX_CARDINALITY_LIMIT = 1 << 29;

  final String name;
  private final int cardinalityLimit;
  private final int capacityMask;

  /**
   * Whether to substitute the {@code tracer_blocked_value} sentinel when the per-cycle budget is
   * exhausted. With limits enabled (sentinel mode), overflow values collapse to one bucket; with
   * limits disabled, the cache size is still bounded by {@link #cardinalityLimit} but over-budget
   * values get freshly-allocated {@link UTF8BytesString}s instead, so the wire format carries the
   * real value and entries don't collapse. Prior-cycle reuse runs in either mode.
   */
  private final boolean useBlockedSentinel;

  // Single open-addressed table per cycle. The stored UTF8BytesString IS the slot identity --
  // equality is checked by comparing its underlying String against the incoming CharSequence.
  private UTF8BytesString[] curValues;
  // Values from the previous reporting cycle, kept so values that persist across cycles can reuse
  // their UTF8BytesString instance without re-allocating.
  private UTF8BytesString[] priorValues;
  private int curSize;

  private UTF8BytesString cacheBlocked = null;
  private String[] statsDTag = null;

  /** Accumulated block count for the current cycle. Returned and zeroed by {@link #reset()}. */
  private long blockedCount;

  /**
   * Test convenience: limits-enabled mode (blocked sentinel substitution active). Production uses
   * the three-argument constructor with the flag from {@code Config}.
   */
  @VisibleForTesting
  PropertyCardinalityHandler(String name, int cardinalityLimit) {
    this(name, cardinalityLimit, true);
  }

  PropertyCardinalityHandler(String name, int cardinalityLimit, boolean useBlockedSentinel) {
    this.name = name;
    if (cardinalityLimit <= 0) {
      throw new IllegalArgumentException("cardinalityLimit must be positive: " + cardinalityLimit);
    }
    if (cardinalityLimit > MAX_CARDINALITY_LIMIT) {
      throw new IllegalArgumentException(
          "cardinalityLimit must be at most " + MAX_CARDINALITY_LIMIT + ": " + cardinalityLimit);
    }
    this.cardinalityLimit = cardinalityLimit;
    this.useBlockedSentinel = useBlockedSentinel;
    // Capacity = next power of two >= 2 * cardinalityLimit. Linear-probing load factor stays
    // <= 0.5 even when the budget is full, which keeps probe chains short.
    final int capacity = Integer.highestOneBit(cardinalityLimit * 2 - 1) << 1;
    this.capacityMask = capacity - 1;
    this.curValues = new UTF8BytesString[capacity];
    this.priorValues = new UTF8BytesString[capacity];
  }

  /**
   * Canonicalizes {@code value} through the cardinality budget and per-cycle reuse cache. Null
   * inputs map to {@link UTF8BytesString#EMPTY} -- {@link AggregateEntry} doesn't need to
   * pre-check.
   *
   * <p>The value hash is computed once and used as the initial probe slot in both tables. {@code h
   * ^ (h >>> 16)} folds high hash bits into the low bits to reduce collisions for inputs that share
   * a common low-bit pattern. {@link UTF8BytesString#hashCode} is content-stable with the
   * underlying String, so a String input and a UTF8BytesString of the same content map to the same
   * slot.
   */
  UTF8BytesString register(CharSequence value) {
    if (value == null) {
      return UTF8BytesString.EMPTY;
    }
    // Initial table slot, used to probe current and prior tables.
    int h = value.hashCode();
    int start = (h ^ (h >>> 16)) & this.capacityMask;

    // First, look in the current-cycle table.
    // If found, this value already consumed cardinality budget in this cycle.
    int slot = start;
    UTF8BytesString existing;
    while ((existing = this.curValues[slot]) != null
        && existing != value
        && !existing.toString().contentEquals(value)) {
      slot = (slot + 1) & this.capacityMask;
    }
    if (existing != null) {
      return existing;
    }
    // This value is new for the current cycle.
    boolean capExhausted = this.curSize >= this.cardinalityLimit;
    // If sentinel mode is enabled and the field is over budget, collapse this
    // value to tracer_blocked_value and count it as blocked.
    if (capExhausted && this.useBlockedSentinel) {
      this.blockedCount++;
      return this.tracerBlockedValue();
    }
    // Otherwise, try to reuse from the prior cycle if possible to avoid re-allocation.
    // Runs whether or not the current budget is exhausted, so persistent values keep their
    // UTF8BytesString instance across cycles.
    int priorSlot = start;
    UTF8BytesString priorMatch;
    while ((priorMatch = this.priorValues[priorSlot]) != null
        && priorMatch != value
        && !priorMatch.toString().contentEquals(value)) {
      priorSlot = (priorSlot + 1) & this.capacityMask;
    }
    UTF8BytesString utf8 = priorMatch != null ? priorMatch : UTF8BytesString.create(value);
    // If there is still budget, remember this value in the current-cycle table.
    if (!capExhausted) {
      this.curValues[slot] = utf8;
      this.curSize += 1;
    }
    // If capExhausted && !useBlockedSentinel, this returns the real value but
    // does not cache it in the current cycle.
    return utf8;
  }

  private UTF8BytesString tracerBlockedValue() {
    UTF8BytesString cacheBlocked = this.cacheBlocked;
    if (cacheBlocked != null) return cacheBlocked;

    this.cacheBlocked = cacheBlocked = UTF8BytesString.create("tracer_blocked_value");
    return cacheBlocked;
  }

  /**
   * Resets the per-cycle working set and returns the accumulated block count for this cycle. The
   * caller is responsible for reporting the count to health metrics if non-zero.
   */
  String[] statsDTag() {
    if (statsDTag == null) {
      statsDTag = new String[] {"collapsed:" + name};
    }
    return statsDTag;
  }

  long reset() {
    long count = this.blockedCount;
    this.blockedCount = 0;
    // Flip pointers: the just-completed cycle becomes prior; what was prior (2 cycles ago) is
/**
 * Cardinality-capped UTF8 encoder and cache for one field in the aggregate key  (@{code value} &rarr; @{code UTF8(value)}).
 *
 * <p>A reporting cycle is the interval between two client-stats flushes. Values seen in the same
 * cycle share one cardinality budget. When the aggregator flushes client stats, this handler is
 * reset for the next cycle.
 * 
 * <p>This handler has two jobs:
 *
 * <ul>
 *   <li>limit the number of distinct values accepted during one reporting cycle;
 *   <li>reuse {@link UTF8BytesString} instances for values seen recently.
 * </ul>
 *
 * <p>The limit is checked before a new UTF8 value is created. When sentinel mode is enabled and the
 * field has already reached {@link #cardinalityLimit} distinct values in the current cycle, a new
 * value is represented as {@code tracer_blocked_value}. That avoids allocating and encoding a value
 * that will be collapsed anyway.
 *
 * <p>The handler keeps two open-addressed tables:
 *
 * <ul>
 *   <li>the current-cycle table, which tracks values that have consumed this cycle's budget;
 *   <li>the prior-cycle table, which keeps the previous cycle's UTF8 values available for reuse.
 * </ul>
 *
 * <p>At reset, the two tables are swapped and the new current-cycle table is cleared. This keeps
 * UTF8 reuse across reporting cycles without copying entries. Stable workloads allocate UTF8 values
 * during the first cycle, then reuse them in later cycles.
 *
 * <p>The tables use linear probing and a capacity of the next power of two greater than or equal to
 * {@code 2 * cardinalityLimit}, so probe chains stay short at the full budget. The stored
 * {@link UTF8BytesString} is also the table key: {@code String} and {@code UTF8BytesString} inputs
 * with the same content map to the same slot because their hashes and equality checks are
 * content-based.
 */
    // recycled into the new (empty) current.
    final UTF8BytesString[] tmp = this.priorValues;
    this.priorValues = this.curValues;
    this.curValues = tmp;
    // Null the new current. The values pulled out of prior are still reachable through any
    // AggregateEntry rows they ended up populating; this just drops the handler's references.
    Arrays.fill(this.curValues, null);
    this.curSize = 0;
    return count;
  }
}

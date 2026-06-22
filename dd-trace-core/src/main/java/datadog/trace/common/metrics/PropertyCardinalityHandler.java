package datadog.trace.common.metrics;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.util.Arrays;

/**
 * Cardinality-capped UTF8 canonicalizer for one property field.
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
 *
 * <p>Accepts any {@link CharSequence} input -- mixed {@code String}/{@code UTF8BytesString} of the
 * same content collapse to one slot because {@link UTF8BytesString#hashCode()} delegates to the
 * underlying String's hash and probe equality is the content-based {@code
 * stored.toString().contentEquals(value)} (which fast-paths to {@code String.equals} when the input
 * is a String).
 *
 * <p><b>Storage:</b> open-addressed flat arrays with linear probing. Two parallel {@code
 * UTF8BytesString[]} tables -- "current cycle" and "prior cycle". Capacity is the next power of two
 * {@code >= 2 * cardinalityLimit} so probes stay short even at the full budget. The stored
 * UTF8BytesString carries the slot's identity directly; no parallel keys array needed.
 *
 * <ul>
 *   <li>The current table tracks which values have consumed a slot of the cardinality budget this
 *       reporting cycle. Once {@link #cardinalityLimit} distinct values are present, further
 *       first-time values get the {@code tracer_blocked_value} sentinel.
 *   <li>The prior table holds the previous cycle's entries verbatim. A first-time-this-cycle value
 *       that hits in the prior table reuses its {@link UTF8BytesString} instance -- no
 *       re-allocation -- and stores that reference in the current table.
 * </ul>
 *
 * <p><b>Reset:</b> swap the current and prior pointers, then null the (now) current. One
 * O(capacity) pass; half the work of a copy-then-null. Workloads with a stable value set across
 * cycles pay zero UTF8 allocations after the first cycle, and the reused instances also
 * short-circuit downstream equality to identity comparisons.
 */
final class PropertyCardinalityHandler {
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
  PropertyCardinalityHandler(String name, int cardinalityLimit) {
    this(name, cardinalityLimit, true);
  }

  PropertyCardinalityHandler(String name, int cardinalityLimit, boolean useBlockedSentinel) {
    this.name = name;
    if (cardinalityLimit <= 0) {
      throw new IllegalArgumentException("cardinalityLimit must be positive: " + cardinalityLimit);
    }
    // Upper bound prevents overflow in the (cardinalityLimit * 2 - 1) capacity calc below.
    // Practical limits are 8..512; this cap is well beyond any realistic configuration.
    if (cardinalityLimit > (1 << 29)) {
      throw new IllegalArgumentException(
          "cardinalityLimit must be at most 2^29: " + cardinalityLimit);
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
   * inputs map to {@link UTF8BytesString#EMPTY} -- callers don't need to pre-check.
   *
   * <p>Hash is computed once and reused as the probe start for both the current-cycle table and (on
   * miss-with-budget) the prior-cycle table; mixing with the upper half ({@code h ^ (h >>> 16)})
   * keeps inputs sharing a low-bit pattern off the same probe chain. {@link
   * UTF8BytesString#hashCode} is content-stable with the underlying String, so a String input and a
   * UTF8BytesString input carrying the same content map to the same slot.
   */
  UTF8BytesString register(CharSequence value) {
    if (value == null) {
      return UTF8BytesString.EMPTY;
    }
    int h = value.hashCode();
    int start = (h ^ (h >>> 16)) & this.capacityMask;

    int slot = start;
    UTF8BytesString existing;
    while ((existing = this.curValues[slot]) != null
        && existing != value
        && !existing.toString().contentEquals(value)) {
      slot = (slot + 1) & this.capacityMask;
    }
    if (existing != null) {
      // Already seen this cycle -- consumed a budget slot earlier; reuse the cached UTF8.
      return existing;
    }
    boolean capExhausted = this.curSize >= this.cardinalityLimit;
    if (capExhausted && this.useBlockedSentinel) {
      this.blockedCount++;
      return this.tracerBlockedValue();
    }
    // Reuse from the prior cycle if possible to avoid re-allocation -- runs whether or not the
    // current budget is exhausted, so persistent values keep their UTF8 instance across cycles.
    int priorSlot = start;
    UTF8BytesString priorMatch;
    while ((priorMatch = this.priorValues[priorSlot]) != null
        && priorMatch != value
        && !priorMatch.toString().contentEquals(value)) {
      priorSlot = (priorSlot + 1) & this.capacityMask;
    }
    UTF8BytesString utf8 = priorMatch != null ? priorMatch : UTF8BytesString.create(value);
    if (!capExhausted) {
      // Budget remaining: claim a slot for future hits this cycle.
      this.curValues[slot] = utf8;
      this.curSize += 1;
    }
    // capExhausted && !useBlockedSentinel: return the value without caching (cache is full).
    // Repeat over-budget values pay the prior-cycle probe each call but skip allocation as long
    // as the prior table still holds them.
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
      statsDTag = new String[] {"tag:" + name};
    }
    return statsDTag;
  }

  long reset() {
    long count = this.blockedCount;
    this.blockedCount = 0;
    // Flip pointers: the just-completed cycle becomes prior; what was prior (2 cycles ago) is
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

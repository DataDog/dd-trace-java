package datadog.trace.common.metrics;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.util.Arrays;

/**
 * Cardinality-capped UTF8 canonicalizer for one property field.
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
 *       first-time values get the {@code blocked_by_tracer} sentinel.
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
public final class PropertyCardinalityHandler {
  private final int cardinalityLimit;
  private final int capacityMask;

  // Single open-addressed table per cycle. The stored UTF8BytesString IS the slot identity --
  // equality is checked by comparing its underlying String against the incoming CharSequence.
  private UTF8BytesString[] curValues;
  private UTF8BytesString[] priorValues;
  private int curSize;

  private UTF8BytesString cacheBlocked = null;

  public PropertyCardinalityHandler(int cardinalityLimit) {
    if (cardinalityLimit <= 0) {
      throw new IllegalArgumentException("cardinalityLimit must be positive: " + cardinalityLimit);
    }
    this.cardinalityLimit = cardinalityLimit;
    // Capacity = next power of two >= 2 * cardinalityLimit. Linear-probing load factor stays
    // <= 0.5 even when the budget is full, which keeps probe chains short.
    final int capacity = Integer.highestOneBit(cardinalityLimit * 2 - 1) << 1;
    this.capacityMask = capacity - 1;
    this.curValues = new UTF8BytesString[capacity];
    this.priorValues = new UTF8BytesString[capacity];
  }

  public UTF8BytesString register(CharSequence value) {
    final int slot = probe(this.curValues, value);
    final UTF8BytesString existing = this.curValues[slot];
    if (existing != null) {
      // Already seen this cycle -- consumed a budget slot earlier; reuse the cached UTF8.
      return existing;
    }
    if (this.curSize >= this.cardinalityLimit) {
      return this.blockedByTracer();
    }
    // First-time-this-cycle value. Reuse from the prior cycle if possible to avoid re-allocation.
    UTF8BytesString utf8;
    final int priorSlot = probe(this.priorValues, value);
    final UTF8BytesString priorMatch = this.priorValues[priorSlot];
    if (priorMatch != null) {
      utf8 = priorMatch;
    } else {
      utf8 = UTF8BytesString.create(value);
    }
    this.curValues[slot] = utf8;
    this.curSize += 1;
    return utf8;
  }

  /**
   * Linear-probe to find {@code value}'s slot: either the slot occupied by a content-equal
   * UTF8BytesString, or the first empty slot in the probe chain. {@link UTF8BytesString#hashCode}
   * is content-stable with the underlying String, so the same content hashes to the same slot
   * regardless of whether the input is a String or UTF8BytesString.
   */
  private int probe(UTF8BytesString[] values, CharSequence value) {
    int idx = value.hashCode() & this.capacityMask;
    while (values[idx] != null && !values[idx].toString().contentEquals(value)) {
      idx = (idx + 1) & this.capacityMask;
    }
    return idx;
  }

  private UTF8BytesString blockedByTracer() {
    UTF8BytesString cacheBlocked = this.cacheBlocked;
    if (cacheBlocked != null) return cacheBlocked;

    this.cacheBlocked = cacheBlocked = UTF8BytesString.create("blocked_by_tracer");
    return cacheBlocked;
  }

  public void reset() {
    // Flip pointers: the just-completed cycle becomes prior; what was prior (2 cycles ago) is
    // recycled into the new (empty) current.
    final UTF8BytesString[] tmp = this.priorValues;
    this.priorValues = this.curValues;
    this.curValues = tmp;
    // Null the new current. The values pulled out of prior are still reachable through any
    // AggregateEntry rows they ended up populating; this just drops the handler's references.
    Arrays.fill(this.curValues, null);
    this.curSize = 0;
  }
}

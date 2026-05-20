package datadog.trace.common.metrics;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.util.Arrays;

/**
 * Cardinality-capped UTF8 canonicalizer for one property field.
 *
 * <p>The type parameter {@code T} pins the input type per handler so the cache key class has
 * well-defined {@code equals}/{@code hashCode} (e.g. {@code String}) rather than the abstract
 * {@code CharSequence} interface, where {@code "foo".equals(UTF8BytesString.create("foo"))} is
 * {@code false}. Each call site uses the type its {@code SpanSnapshot} field carries; the compiler
 * then enforces type consistency across calls to a given handler.
 *
 * <p><b>Storage:</b> open-addressed flat arrays with linear probing. Two parallel tables --
 * "current cycle" and "prior cycle". Capacity is the next power of two {@code >= 2 *
 * cardinalityLimit} so probes stay short even when the budget is full.
 *
 * <ul>
 *   <li>The current table tracks which values have consumed a slot of the cardinality budget this
 *       reporting cycle. Once {@link #cardinalityLimit} distinct values are present, further
 *       first-time values get the {@code blocked_by_tracer} sentinel.
 *   <li>The prior table holds the previous cycle's entries verbatim. A first-time-this-cycle value
 *       that hits in the prior table reuses its {@link UTF8BytesString} instance -- no
 *       re-allocation -- and inserts a reference into the current table.
 * </ul>
 *
 * <p><b>Reset:</b> swap the current and prior pointers, then null the (now) current. This is one
 * O(capacity) pass rather than the two passes a copy-then-null would need. Workloads with a stable
 * value set across cycles pay zero UTF8 allocations after the first cycle; the reused instances
 * also short-circuit downstream equality to identity comparisons.
 */
public final class PropertyCardinalityHandler<T extends CharSequence> {
  private final int cardinalityLimit;
  private final int capacityMask;

  // Open-addressed parallel arrays. keys[i] == null means the slot is empty; otherwise
  // values[i] holds the canonical UTF8 for keys[i]. Object[] rather than T[] so we can swap
  // refs without unchecked-array-of-generic gymnastics.
  private Object[] curKeys;
  private UTF8BytesString[] curValues;
  private Object[] priorKeys;
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
    this.curKeys = new Object[capacity];
    this.curValues = new UTF8BytesString[capacity];
    this.priorKeys = new Object[capacity];
    this.priorValues = new UTF8BytesString[capacity];
  }

  public UTF8BytesString register(T value) {
    final int slot = probe(this.curKeys, value);
    if (this.curKeys[slot] != null) {
      // Already seen this cycle -- consumed a budget slot earlier; reuse the cached UTF8.
      return this.curValues[slot];
    }
    if (this.curSize >= this.cardinalityLimit) {
      return this.blockedByTracer();
    }
    // First-time-this-cycle value. Reuse from the prior cycle if possible to avoid re-allocation.
    UTF8BytesString utf8;
    final int priorSlot = probe(this.priorKeys, value);
    if (this.priorKeys[priorSlot] != null) {
      utf8 = this.priorValues[priorSlot];
    } else {
      utf8 = UTF8BytesString.create(value);
    }
    this.curKeys[slot] = value;
    this.curValues[slot] = utf8;
    this.curSize += 1;
    return utf8;
  }

  /**
   * Linear-probe to find {@code value}'s slot: either the slot occupied by an equal key, or the
   * first empty slot in the probe chain. Capacity is a power of two; mask with {@link
   * #capacityMask}.
   */
  private int probe(Object[] keys, T value) {
    int idx = value.hashCode() & this.capacityMask;
    while (keys[idx] != null && !keys[idx].equals(value)) {
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
    final Object[] tmpKeys = this.priorKeys;
    final UTF8BytesString[] tmpValues = this.priorValues;
    this.priorKeys = this.curKeys;
    this.priorValues = this.curValues;
    this.curKeys = tmpKeys;
    this.curValues = tmpValues;
    // Null the new current. The values pulled out of prior are still reachable through any
    // AggregateEntry rows they ended up populating; this just drops the handler's references.
    Arrays.fill(this.curKeys, null);
    Arrays.fill(this.curValues, null);
    this.curSize = 0;
  }
}

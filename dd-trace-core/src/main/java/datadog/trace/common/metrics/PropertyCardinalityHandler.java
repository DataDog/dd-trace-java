package datadog.trace.common.metrics;

import static datadog.trace.common.metrics.CardinalityTables.capacityFor;
import static datadog.trace.common.metrics.CardinalityTables.maxCapacity;

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

  /**
   * Protobuf field name this handler reports under in the {@code collapsed:} health/telemetry tag,
   * per the approved Cardinality Limits RFC (section 5), which keys collapses by the lowercased
   * protobuf field name. Usually equal to {@link #name}, but decoupled where the field's
   * human-facing name differs from its wire name -- e.g. {@code operation} is carried on the wire
   * as {@code name}, so its telemetry tag is {@code collapsed:name} while the human-facing {@link
   * CardinalityLimitReporter} still says {@code operation}.
   */
  private final String statsDField;

  private final int cardinalityLimit;

  /** The largest table, holding a full budget of values at the 0.5 load factor. */
  private final int maxCapacity;

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
  // Tables are sized to the values seen, not the budget: null until the first value, they start
  // at CardinalityTables.MIN_CAPACITY and double up to maxCapacity, and each reset sizes the next
  // cycle's table for as many values as the cycle just completed held.
  private UTF8BytesString[] curValues;
  // Values from the previous reporting cycle, kept so values that persist across cycles can reuse
  // their UTF8BytesString instance without re-allocating. Null if that cycle saw no values.
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
    this(name, name, cardinalityLimit, useBlockedSentinel);
  }

  PropertyCardinalityHandler(
      String name, String statsDField, int cardinalityLimit, boolean useBlockedSentinel) {
    this.name = name;
    this.statsDField = statsDField;
    if (cardinalityLimit <= 0) {
      throw new IllegalArgumentException("cardinalityLimit must be positive: " + cardinalityLimit);
    }
    if (cardinalityLimit > MAX_CARDINALITY_LIMIT) {
      throw new IllegalArgumentException(
          "cardinalityLimit must be at most " + MAX_CARDINALITY_LIMIT + ": " + cardinalityLimit);
    }
    this.cardinalityLimit = cardinalityLimit;
    this.useBlockedSentinel = useBlockedSentinel;
    // Max capacity = next power of two >= 2 * cardinalityLimit. Linear-probing load factor stays
    // <= 0.5 even when the budget is full, which keeps probe chains short.
    this.maxCapacity = maxCapacity(cardinalityLimit);
  }

  /**
   * Canonicalizes {@code value} through the cardinality budget and per-cycle reuse cache. Null
   * inputs map to {@link UTF8BytesString#EMPTY} -- {@link AggregateEntry} doesn't need to
   * pre-check.
   *
   * <p>The spread hash is computed once and masked to each table's own size to give its initial
   * probe slot. {@code h ^ (h >>> 16)} folds high hash bits into the low bits to reduce collisions
   * for inputs that share a common low-bit pattern. The hash is always the content's {@code String}
   * hash (see {@link #contentHash}), the same one {@link #grow} rehashes stored values by, so any
   * {@code CharSequence} of the same content maps to the same slot.
   */
  UTF8BytesString register(CharSequence value) {
    if (value == null) {
      return UTF8BytesString.EMPTY;
    }
    // Spread hash, used to probe current and prior tables.
    int h = contentHash(value);
    int spread = h ^ (h >>> 16);

    // First, look in the current-cycle table.
    // If found, this value already consumed cardinality budget in this cycle.
    UTF8BytesString[] cur = this.curValues;
    int slot = -1;
    if (cur != null) {
      int mask = cur.length - 1;
      slot = spread & mask;
      UTF8BytesString existing;
      while ((existing = cur[slot]) != null) {
        if (existing == value || existing.toString().contentEquals(value)) {
          return existing;
        }
        slot = (slot + 1) & mask;
      }
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
    UTF8BytesString priorMatch = findPrior(value, spread);
    UTF8BytesString utf8 = priorMatch != null ? priorMatch : UTF8BytesString.create(value);
    // If there is still budget, remember this value in the current-cycle table, growing it first
    // if this value would take it past the 0.5 load factor.
    if (!capExhausted) {
      if (cur == null || (this.curSize + 1) * 2 > cur.length) {
        cur = grow(cur);
        slot = findFreeSlot(cur, spread);
      }
      cur[slot] = utf8;
      this.curSize += 1;
    }
    // If capExhausted && !useBlockedSentinel, this returns the real value but
    // does not cache it in the current cycle.
    return utf8;
  }

  /**
   * The {@code String} hash of {@code value}'s content. {@code String} and {@link UTF8BytesString}
   * already hash that way; other {@code CharSequence}s ({@code StringBuilder}, {@code CharBuffer})
   * may not, and probing with their own hash would miss a stored value after the table grows.
   */
  private static int contentHash(CharSequence value) {
    if (value instanceof String || value instanceof UTF8BytesString) {
      return value.hashCode();
    }
    int h = 0;
    for (int i = 0; i < value.length(); i++) {
      h = 31 * h + value.charAt(i);
    }
    return h;
  }

  private UTF8BytesString findPrior(CharSequence value, int spread) {
    UTF8BytesString[] prior = this.priorValues;
    if (prior == null) {
      return null;
    }
    int mask = prior.length - 1;
    int slot = spread & mask;
    UTF8BytesString match;
    while ((match = prior[slot]) != null) {
      if (match == value || match.toString().contentEquals(value)) {
        return match;
      }
      slot = (slot + 1) & mask;
    }
    return null;
  }

  /** Doubles the current table, or creates the smallest one, and rehashes its values. */
  private UTF8BytesString[] grow(UTF8BytesString[] old) {
    int capacity =
        old == null ? capacityFor(1, this.maxCapacity) : Math.min(this.maxCapacity, old.length * 2);
    UTF8BytesString[] grown = new UTF8BytesString[capacity];
    if (old != null) {
      for (UTF8BytesString utf8 : old) {
        if (utf8 != null) {
          int h = utf8.hashCode();
          grown[findFreeSlot(grown, h ^ (h >>> 16))] = utf8;
        }
      }
    }
    this.curValues = grown;
    return grown;
  }

  private static int findFreeSlot(UTF8BytesString[] table, int spread) {
    int mask = table.length - 1;
    int slot = spread & mask;
    while (table[slot] != null) {
      slot = (slot + 1) & mask;
    }
    return slot;
  }

  /** The size of the current-cycle table, or 0 while the handler holds none. */
  @VisibleForTesting
  int tableCapacity() {
    return this.curValues == null ? 0 : this.curValues.length;
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
      statsDTag = new String[] {"collapsed:" + statsDField};
    }
    return statsDTag;
  }

  long reset() {
    long count = this.blockedCount;
    this.blockedCount = 0;
    // The just-completed cycle becomes prior. The new current is sized for as many values as that
    // cycle held, recycling what was prior (2 cycles ago) when it's already that size; a cycle
    // with no values releases both tables. The values dropped here are still reachable through
    // any AggregateEntry rows they ended up populating; this just drops the handler's references.
    final UTF8BytesString[] recycled = this.priorValues;
    final int used = this.curSize;
    this.priorValues = used > 0 ? this.curValues : null;
    if (used == 0) {
      this.curValues = null;
    } else {
      int capacity = capacityFor(used, this.maxCapacity);
      if (recycled != null && recycled.length == capacity) {
        Arrays.fill(recycled, null);
        this.curValues = recycled;
      } else {
        this.curValues = new UTF8BytesString[capacity];
      }
    }
    this.curSize = 0;
    return count;
  }
}

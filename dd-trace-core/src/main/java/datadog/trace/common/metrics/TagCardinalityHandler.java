package datadog.trace.common.metrics;

import static datadog.trace.common.metrics.CardinalityTables.capacityFor;
import static datadog.trace.common.metrics.CardinalityTables.maxCapacity;

import datadog.trace.api.internal.VisibleForTesting;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Arrays;

/**
 * Cardinality-capped UTF8 encoder and cache for one peer-tag name ({@code value} &rarr; {@code
 * UTF8("tag:value")}).
 *
 * <p>Same per-cycle budget and prior-cycle reuse as {@link PropertyCardinalityHandler}. The
 * difference is that the cached output is the pre-encoded {@code "tag:value"} string, so a parallel
 * raw-value keys table is needed alongside the UTF8 values table.
 */
final class TagCardinalityHandler {
  // Upper bound prevents int overflow in the (cardinalityLimit * 2 - 1) capacity calculation.
  // Practical limits are 8..512; this cap is well beyond any realistic configuration.
  private static final int MAX_CARDINALITY_LIMIT = 1 << 29;

  private final String tag;
  private final int cardinalityLimit;
  private final int maxValueLength;

  /** The largest table, holding a full budget of values at the 0.5 load factor. */
  private final int maxCapacity;

  /** See {@link PropertyCardinalityHandler}'s field of the same name. */
  private final boolean useBlockedSentinel;

  // Parallel open-addressed tables per cycle, sized to the values seen rather than the budget, as
  // in PropertyCardinalityHandler: null until the first value, then from the minimum size doubling
  // up to maxCapacity, with each reset sizing the next cycle's tables for what the last one held.
  // A peer tag the service never emits costs no tables at all.
  private String[] curKeys;
  private UTF8BytesString[] curValues;
  private String[] priorKeys;
  private UTF8BytesString[] priorValues;
  private int curSize;

  private UTF8BytesString cacheBlocked = null;

  /**
   * Values collapsed by the per-cycle cardinality (distinct-value) budget in the current cycle.
   * Returned and zeroed by {@link #reset()}. Surfaces as the {@code collapsed:} health-metric tag.
   */
  private long collapsedCount;

  /**
   * Values collapsed by the per-value length cap in the current cycle. Always 0 when {@code
   * maxValueLength} is unbounded (e.g. peer tags). Read via {@link #oversizedCount()} before {@link
   * #reset()} and surfaces as the {@code oversized:} health-metric tag. Kept separate from {@link
   * #collapsedCount} because the approved Cardinality Limits RFC tags length-collapses ({@code
   * oversized:}) distinctly from cardinality-collapses ({@code collapsed:}).
   */
  private long oversizedCount;

  /**
   * Test convenience: limits-enabled mode, no per-value length cap. Production uses the
   * four-argument constructor.
   */
  @VisibleForTesting
  TagCardinalityHandler(String tag, int cardinalityLimit) {
    this(tag, cardinalityLimit, true, Integer.MAX_VALUE);
  }

  TagCardinalityHandler(String tag, int cardinalityLimit, boolean useBlockedSentinel) {
    this(tag, cardinalityLimit, useBlockedSentinel, Integer.MAX_VALUE);
  }

  TagCardinalityHandler(
      String tag, int cardinalityLimit, boolean useBlockedSentinel, int maxValueLength) {
    if (cardinalityLimit <= 0) {
      throw new IllegalArgumentException("cardinalityLimit must be positive: " + cardinalityLimit);
    }
    if (cardinalityLimit > MAX_CARDINALITY_LIMIT) {
      throw new IllegalArgumentException(
          "cardinalityLimit must be at most " + MAX_CARDINALITY_LIMIT + ": " + cardinalityLimit);
    }
    this.tag = tag;
    this.cardinalityLimit = cardinalityLimit;
    this.useBlockedSentinel = useBlockedSentinel;
    this.maxValueLength = maxValueLength;
    this.maxCapacity = maxCapacity(cardinalityLimit);
  }

  /**
   * Returns the UTF8 value to use for {@code value} in the current reporting cycle. Null inputs are
   * returned as {@link UTF8BytesString#EMPTY}.
   *
   * <p>The spread hash is computed once and masked to each table's own size to give its initial
   * probe slot. The {@code h ^ (h >>> 16)} calculation folds high hash bits into the low bits,
   * which reduces clustering when values share similar low-bit hash patterns.
   */
  @SuppressFBWarnings(
      value = "ES_COMPARING_PARAMETER_STRING_WITH_EQ",
      justification =
          "Intentional identity fast-path: the reference check short-circuits the .equals() call"
              + " when the stored key and probe value are the same instance.")
  UTF8BytesString register(String value) {
    if (value == null) {
      return UTF8BytesString.EMPTY;
    }
    // Values exceeding the length cap are blocked regardless of cardinality budget: application
    // code can accidentally populate a tag with a stack trace, SQL statement, or JSON blob.
    if (value.length() > this.maxValueLength && this.useBlockedSentinel) {
      this.oversizedCount++;
      return this.tracerBlockedValue();
    }
    // Compute the spread hash once. It probes the current-cycle table and, on miss, the
    // prior-cycle table, each masked to its own size.
    int h = value.hashCode();
    int spread = h ^ (h >>> 16);

    // Look for the raw value in the current-cycle table. If found, return the already encoded
    // "tag:value" UTF8 value.
    String[] keys = this.curKeys;
    int slot = -1;
    if (keys != null) {
      int mask = keys.length - 1;
      slot = spread & mask;
      String existing;
      while ((existing = keys[slot]) != null) {
        if (existing == value || existing.equals(value)) {
          return this.curValues[slot];
        }
        slot = (slot + 1) & mask;
      }
    }
    // This value is new for the current cycle.
    boolean capExhausted = this.curSize >= this.cardinalityLimit;
    // If sentinel mode is enabled and the tag has reached its value budget,
    // collapse this value to "tag:tracer_blocked_value" and record the block.
    if (capExhausted && this.useBlockedSentinel) {
      this.collapsedCount++;
      return this.tracerBlockedValue();
    }
    // Reuse the previous cycle's encoded "tag:value" UTF8 value if present; otherwise
    // create it from the fixed tag name and the raw value.
    UTF8BytesString prior = findPrior(value, spread);
    UTF8BytesString utf8 = prior != null ? prior : UTF8BytesString.create(this.tag + ":" + value);
    // If still within budget, remember the raw value and its encoded UTF8 output in the
    // current-cycle tables, growing them first if this value would pass the 0.5 load factor.
    if (!capExhausted) {
      if (keys == null || (this.curSize + 1) * 2 > keys.length) {
        keys = grow();
        slot = findFreeSlot(keys, spread);
      }
      keys[slot] = value;
      this.curValues[slot] = utf8;
      this.curSize += 1;
    }
    // If capExhausted && !useBlockedSentinel, return the real "tag:value" value
    // without caching it in the current cycle.
    return utf8;
  }

  private UTF8BytesString findPrior(String value, int spread) {
    String[] keys = this.priorKeys;
    if (keys == null) {
      return null;
    }
    int mask = keys.length - 1;
    int slot = spread & mask;
    String key;
    while ((key = keys[slot]) != null) {
      if (key == value || key.equals(value)) {
        return this.priorValues[slot];
      }
      slot = (slot + 1) & mask;
    }
    return null;
  }

  /** Doubles the current tables, or creates the smallest ones, rehashing their entries. */
  private String[] grow() {
    String[] oldKeys = this.curKeys;
    UTF8BytesString[] oldValues = this.curValues;
    int capacity =
        oldKeys == null
            ? capacityFor(1, this.maxCapacity)
            : Math.min(this.maxCapacity, oldKeys.length * 2);
    String[] keys = new String[capacity];
    UTF8BytesString[] values = new UTF8BytesString[capacity];
    if (oldKeys != null) {
      for (int i = 0; i < oldKeys.length; ++i) {
        String key = oldKeys[i];
        if (key != null) {
          int h = key.hashCode();
          int slot = findFreeSlot(keys, h ^ (h >>> 16));
          keys[slot] = key;
          values[slot] = oldValues[i];
        }
      }
    }
    this.curKeys = keys;
    this.curValues = values;
    return keys;
  }

  private static int findFreeSlot(String[] keys, int spread) {
    int mask = keys.length - 1;
    int slot = spread & mask;
    while (keys[slot] != null) {
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

    this.cacheBlocked = cacheBlocked = UTF8BytesString.create(this.tag + ":tracer_blocked_value");
    return cacheBlocked;
  }

  /**
   * Number of values collapsed by the per-value length cap in the current cycle. Must be read
   * before {@link #reset()}, which zeroes it along with the cardinality count.
   */
  long oversizedCount() {
    return this.oversizedCount;
  }

  /**
   * Resets the per-cycle working set and returns the cardinality-collapse count for this cycle (the
   * {@code collapsed:} count). Also zeroes the length-collapse count, so callers that report it
   * must read {@link #oversizedCount()} first. The caller reports both counts to health metrics if
   * non-zero.
   */
  long reset() {
    long count = this.collapsedCount;
    this.collapsedCount = 0;
    this.oversizedCount = 0;
    // The just-completed cycle becomes prior. The new current is sized for as many values as that
    // cycle held, recycling what was prior (2 cycles ago) when it's already that size; a cycle
    // with no values releases all the tables.
    final String[] recycledKeys = this.priorKeys;
    final UTF8BytesString[] recycledValues = this.priorValues;
    final int used = this.curSize;
    this.priorKeys = used > 0 ? this.curKeys : null;
    this.priorValues = used > 0 ? this.curValues : null;
    if (used == 0) {
      this.curKeys = null;
      this.curValues = null;
    } else {
      int capacity = capacityFor(used, this.maxCapacity);
      if (recycledKeys != null && recycledKeys.length == capacity) {
        Arrays.fill(recycledKeys, null);
        Arrays.fill(recycledValues, null);
        this.curKeys = recycledKeys;
        this.curValues = recycledValues;
      } else {
        this.curKeys = new String[capacity];
        this.curValues = new UTF8BytesString[capacity];
      }
    }
    this.curSize = 0;
    return count;
  }
}

package datadog.trace.common.metrics;

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
  private String[] statsDTag = null;
  private final int cardinalityLimit;
  private final int capacityMask;

  /** See {@link PropertyCardinalityHandler}'s field of the same name. */
  private final boolean useBlockedSentinel;

  private String[] curKeys;
  private UTF8BytesString[] curValues;
  private String[] priorKeys;
  private UTF8BytesString[] priorValues;
  private int curSize;

  private UTF8BytesString cacheBlocked = null;

  /** Accumulated block count for the current cycle. Returned and zeroed by {@link #reset()}. */
  private long blockedCount;

  /**
   * Test convenience: limits-enabled mode. Production uses the three-argument constructor with the
   * flag from {@code Config}.
   */
  @VisibleForTesting
  TagCardinalityHandler(String tag, int cardinalityLimit) {
    this(tag, cardinalityLimit, true);
  }

  TagCardinalityHandler(String tag, int cardinalityLimit, boolean useBlockedSentinel) {
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
    final int capacity = Integer.highestOneBit(cardinalityLimit * 2 - 1) << 1;
    this.capacityMask = capacity - 1;
    this.curKeys = new String[capacity];
    this.curValues = new UTF8BytesString[capacity];
    this.priorKeys = new String[capacity];
    this.priorValues = new UTF8BytesString[capacity];
  }

  /**
   * Returns the UTF8 value to use for {@code value} in the current reporting cycle. Null inputs are
   * returned as {@link UTF8BytesString#EMPTY}.
   *
   * <p>The value hash is computed once and used as the initial probe slot in both tables. The
   * {@code h ^ (h >>> 16)} calculation folds high hash bits into the low bits, which reduces
   * clustering when values share similar low-bit hash patterns.
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
    // Compute the initial probe slot once. The same start slot is used for the
    // current-cycle table and, on miss, for the prior-cycle table.
    int h = value.hashCode();
    int start = (h ^ (h >>> 16)) & this.capacityMask;

    // Look for the raw value in the current-cycle table.
    int slot = start;
    String existing;
    while ((existing = this.curKeys[slot]) != null
        && existing != value
        && !existing.equals(value)) {
      slot = (slot + 1) & this.capacityMask;
    }
    // If found, return the already encoded "tag:value" UTF8 value.
    if (existing != null) {
      return this.curValues[slot];
    }
    // This value is new for the current cycle.
    boolean capExhausted = this.curSize >= this.cardinalityLimit;
    // If sentinel mode is enabled and the tag has reached its value budget,
    // collapse this value to "tag:tracer_blocked_value" and record the block.
    if (capExhausted && this.useBlockedSentinel) {
      this.blockedCount++;
      return this.tracerBlockedValue();
    }
    // Try to find the same raw value in the previous-cycle table so the encoded
    // UTF8 object can be reused after a reset.
    int priorSlot = start;
    String priorKey;
    while ((priorKey = this.priorKeys[priorSlot]) != null
        && priorKey != value
        && !priorKey.equals(value)) {
      priorSlot = (priorSlot + 1) & this.capacityMask;
    }
    // Reuse the previous encoded "tag:value" UTF8 value if present; otherwise
    // create it from the fixed tag name and the raw value.
    UTF8BytesString utf8 =
        priorKey != null
            ? this.priorValues[priorSlot]
            : UTF8BytesString.create(this.tag + ":" + value);
    // If still within budget, remember the raw value and its encoded UTF8
    // output in the current-cycle table.
    if (!capExhausted) {
      this.curKeys[slot] = value;
      this.curValues[slot] = utf8;
      this.curSize += 1;
    }
    // If capExhausted && !useBlockedSentinel, return the real "tag:value" value
    // without caching it in the current cycle.
    return utf8;
  }

  private UTF8BytesString tracerBlockedValue() {
    UTF8BytesString cacheBlocked = this.cacheBlocked;
    if (cacheBlocked != null) return cacheBlocked;

    this.cacheBlocked = cacheBlocked = UTF8BytesString.create(this.tag + ":tracer_blocked_value");
    return cacheBlocked;
  }

  String[] statsDTag() {
    if (statsDTag == null) {
      statsDTag = new String[] {"collapsed:" + tag};
    }
    return statsDTag;
  }

  /**
   * Resets the per-cycle working set and returns the accumulated block count for this cycle. The
   * caller is responsible for reporting the count to health metrics if non-zero.
   */
  long reset() {
    long count = this.blockedCount;
    this.blockedCount = 0;
    final String[] tmpKeys = this.priorKeys;
    final UTF8BytesString[] tmpValues = this.priorValues;
    this.priorKeys = this.curKeys;
    this.priorValues = this.curValues;
    this.curKeys = tmpKeys;
    this.curValues = tmpValues;
    Arrays.fill(this.curKeys, null);
    Arrays.fill(this.curValues, null);
    this.curSize = 0;
    return count;
  }
}

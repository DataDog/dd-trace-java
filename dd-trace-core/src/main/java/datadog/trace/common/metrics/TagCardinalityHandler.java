package datadog.trace.common.metrics;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Arrays;

/**
 * Cardinality-capped UTF8 canonicalizer for one peer-tag name. Output is the pre-encoded {@code
 * "tag:value"} form the serializer writes.
 *
 * <p>Like {@link PropertyCardinalityHandler}, this serves a dual role -- cardinality limiter and
 * UTF8 cache fused into one set of recently used values, with the prior cycle's entries retained so
 * UTF8 reuse survives the per-cycle reset. See {@link PropertyCardinalityHandler} for the full
 * rationale and storage layout.
 *
 * <p>The structural difference here is that the cached {@link UTF8BytesString} holds the {@code
 * "tag:value"} concatenation rather than the bare value, so a parallel {@code String[]} keys table
 * is needed to probe by the raw value.
 */
final class TagCardinalityHandler {
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
  TagCardinalityHandler(String tag, int cardinalityLimit) {
    this(tag, cardinalityLimit, true);
  }

  TagCardinalityHandler(String tag, int cardinalityLimit, boolean useBlockedSentinel) {
    if (cardinalityLimit <= 0) {
      throw new IllegalArgumentException("cardinalityLimit must be positive: " + cardinalityLimit);
    }
    // Upper bound prevents overflow in the (cardinalityLimit * 2 - 1) capacity calc below.
    if (cardinalityLimit > (1 << 29)) {
      throw new IllegalArgumentException(
          "cardinalityLimit must be at most 2^29: " + cardinalityLimit);
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
   * Canonicalizes {@code value} through the cardinality budget and per-cycle reuse cache. Null
   * inputs map to {@link UTF8BytesString#EMPTY} -- callers don't need to pre-check.
   *
   * <p>Hash is computed once and reused as the probe start for both the current-cycle table and (on
   * miss-with-budget) the prior-cycle table; mixing with the upper half ({@code h ^ (h >>> 16)})
   * keeps inputs sharing a low-bit pattern off the same probe chain.
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
    int h = value.hashCode();
    int start = (h ^ (h >>> 16)) & this.capacityMask;

    int slot = start;
    String curKey;
    while ((curKey = this.curKeys[slot]) != null && curKey != value && !curKey.equals(value)) {
      slot = (slot + 1) & this.capacityMask;
    }
    if (curKey != null) {
      return this.curValues[slot];
    }
    boolean capExhausted = this.curSize >= this.cardinalityLimit;
    if (capExhausted && this.useBlockedSentinel) {
      this.blockedCount++;
      return this.blockedByTracer();
    }
    int priorSlot = start;
    String priorKey;
    while ((priorKey = this.priorKeys[priorSlot]) != null
        && priorKey != value
        && !priorKey.equals(value)) {
      priorSlot = (priorSlot + 1) & this.capacityMask;
    }
    UTF8BytesString utf8 =
        priorKey != null
            ? this.priorValues[priorSlot]
            : UTF8BytesString.create(this.tag + ":" + value);
    if (!capExhausted) {
      this.curKeys[slot] = value;
      this.curValues[slot] = utf8;
      this.curSize += 1;
    }
    return utf8;
  }

  private UTF8BytesString blockedByTracer() {
    UTF8BytesString cacheBlocked = this.cacheBlocked;
    if (cacheBlocked != null) return cacheBlocked;

    this.cacheBlocked = cacheBlocked = UTF8BytesString.create(this.tag + ":blocked_by_tracer");
    return cacheBlocked;
  }

  String[] statsDTag() {
    if (statsDTag == null) {
      statsDTag = new String[] {"tag:" + tag};
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

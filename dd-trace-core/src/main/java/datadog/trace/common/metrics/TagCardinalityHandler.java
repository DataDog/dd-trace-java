package datadog.trace.common.metrics;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.util.Arrays;

/**
 * Cardinality-capped UTF8 canonicalizer for one peer-tag name. Output is the pre-encoded {@code
 * "tag:value"} form the serializer writes.
 *
 * <p>Same open-addressed flat-array + prior-cycle reuse design as {@link
 * PropertyCardinalityHandler} -- see that class for full description.
 */
final class TagCardinalityHandler {
  private final String tag;
  private final int cardinalityLimit;
  private final int capacityMask;

  private String[] curKeys;
  private UTF8BytesString[] curValues;
  private String[] priorKeys;
  private UTF8BytesString[] priorValues;
  private int curSize;

  private UTF8BytesString cacheBlocked = null;

  TagCardinalityHandler(String tag, int cardinalityLimit) {
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
   */
  UTF8BytesString register(String value) {
    if (value == null) {
      return UTF8BytesString.EMPTY;
    }
    final int slot = probe(this.curKeys, value);
    if (this.curKeys[slot] != null) {
      return this.curValues[slot];
    }
    if (this.curSize >= this.cardinalityLimit) {
      return this.blockedByTracer();
    }
    UTF8BytesString utf8;
    final int priorSlot = probe(this.priorKeys, value);
    if (this.priorKeys[priorSlot] != null) {
      utf8 = this.priorValues[priorSlot];
    } else {
      utf8 = UTF8BytesString.create(this.tag + ":" + value);
    }
    this.curKeys[slot] = value;
    this.curValues[slot] = utf8;
    this.curSize += 1;
    return utf8;
  }

  private int probe(String[] keys, String value) {
    int idx = value.hashCode() & this.capacityMask;
    while (keys[idx] != null && !keys[idx].equals(value)) {
      idx = (idx + 1) & this.capacityMask;
    }
    return idx;
  }

  /**
   * Whether {@code result} (returned from a prior {@link #register} call) is this handler's
   * blocked sentinel. The size check short-circuits the hot path so the sentinel is never
   * materialized before any value has actually been blocked this cycle.
   */
  boolean isBlockedResult(UTF8BytesString result) {
    return this.curSize >= this.cardinalityLimit && result == blockedByTracer();
  }

  private UTF8BytesString blockedByTracer() {
    UTF8BytesString cacheBlocked = this.cacheBlocked;
    if (cacheBlocked != null) return cacheBlocked;

    this.cacheBlocked = cacheBlocked = UTF8BytesString.create(this.tag + ":blocked_by_tracer");
    return cacheBlocked;
  }

  void reset() {
    final String[] tmpKeys = this.priorKeys;
    final UTF8BytesString[] tmpValues = this.priorValues;
    this.priorKeys = this.curKeys;
    this.priorValues = this.curValues;
    this.curKeys = tmpKeys;
    this.curValues = tmpValues;
    Arrays.fill(this.curKeys, null);
    Arrays.fill(this.curValues, null);
    this.curSize = 0;
  }
}

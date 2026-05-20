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
public final class TagCardinalityHandler {
  private final String tag;
  private final int cardinalityLimit;
  private final int capacityMask;

  private Object[] curKeys;
  private UTF8BytesString[] curValues;
  private Object[] priorKeys;
  private UTF8BytesString[] priorValues;
  private int curSize;

  private UTF8BytesString cacheBlocked = null;

  public TagCardinalityHandler(String tag, int cardinalityLimit) {
    if (cardinalityLimit <= 0) {
      throw new IllegalArgumentException("cardinalityLimit must be positive: " + cardinalityLimit);
    }
    this.tag = tag;
    this.cardinalityLimit = cardinalityLimit;
    final int capacity = Integer.highestOneBit(cardinalityLimit * 2 - 1) << 1;
    this.capacityMask = capacity - 1;
    this.curKeys = new Object[capacity];
    this.curValues = new UTF8BytesString[capacity];
    this.priorKeys = new Object[capacity];
    this.priorValues = new UTF8BytesString[capacity];
  }

  public UTF8BytesString register(String value) {
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

  private int probe(Object[] keys, String value) {
    int idx = value.hashCode() & this.capacityMask;
    while (keys[idx] != null && !keys[idx].equals(value)) {
      idx = (idx + 1) & this.capacityMask;
    }
    return idx;
  }

  private UTF8BytesString blockedByTracer() {
    UTF8BytesString cacheBlocked = this.cacheBlocked;
    if (cacheBlocked != null) return cacheBlocked;

    this.cacheBlocked = cacheBlocked = UTF8BytesString.create(this.tag + ":blocked_by_tracer");
    return cacheBlocked;
  }

  public void reset() {
    final Object[] tmpKeys = this.priorKeys;
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

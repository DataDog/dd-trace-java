package datadog.trace.common.metrics;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.util.Arrays;

/**
 * Interns {@code tag:value} pairs into {@link UTF8BytesString}s, capped at {@code
 * cardinalityLimit} distinct values per reporting cycle. Once the cap is hit, every {@link
 * #register} call returns a shared {@code tag:blocked_by_tracer} sentinel.
 *
 * <p>Backed by a flat open-addressed table sized to {@code 2 * cardinalityLimit} (rounded up to a
 * power of two). Not thread-safe -- only the aggregator thread calls these.
 */
public final class TagCardinalityHandler {

  private final String tag;
  private final int cardinalityLimit;
  private final String[] keys;
  private final UTF8BytesString[] values;
  private final int mask;

  private int size;
  private UTF8BytesString cacheBlocked;

  public TagCardinalityHandler(String tag, int cardinalityLimit) {
    this.tag = tag;
    this.cardinalityLimit = cardinalityLimit;
    int cap = tableSizeFor(cardinalityLimit);
    this.keys = new String[cap];
    this.values = new UTF8BytesString[cap];
    this.mask = cap - 1;
  }

  public UTF8BytesString register(String value) {
    if (size >= cardinalityLimit) {
      return blockedByTracer();
    }
    int i = value.hashCode() & mask;
    while (true) {
      String k = keys[i];
      if (k == null) {
        UTF8BytesString newPair = UTF8BytesString.create(tag + ":" + value);
        keys[i] = value;
        values[i] = newPair;
        size++;
        return newPair;
      }
      if (k.equals(value)) {
        return values[i];
      }
      i = (i + 1) & mask;
    }
  }

  private UTF8BytesString blockedByTracer() {
    UTF8BytesString cached = this.cacheBlocked;
    if (cached != null) {
      return cached;
    }
    cached = UTF8BytesString.create(tag + ":blocked_by_tracer");
    this.cacheBlocked = cached;
    return cached;
  }

  public void reset() {
    if (size > 0) {
      Arrays.fill(keys, null);
      Arrays.fill(values, null);
      size = 0;
    }
  }

  private static int tableSizeFor(int cardinalityLimit) {
    int target = Math.max(cardinalityLimit * 2, 16);
    return Integer.highestOneBit(target - 1) << 1;
  }
}

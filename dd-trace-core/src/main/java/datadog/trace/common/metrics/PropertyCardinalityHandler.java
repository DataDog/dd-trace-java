package datadog.trace.common.metrics;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.util.Arrays;

/**
 * Interns raw label values into {@link UTF8BytesString}s, capped at {@code cardinalityLimit}
 * distinct values per reporting cycle. Once the cap is hit, every {@link #register} call returns a
 * shared {@code blocked_by_tracer} sentinel -- including for values that were registered before
 * the cap was reached -- so the consumer's hash key collapses to one bucket for all overflow.
 *
 * <p>Backed by a flat open-addressed table sized to {@code 2 * cardinalityLimit} (rounded up to a
 * power of two). Avoids the {@code HashMap$Node} allocations of the previous implementation; reset
 * just nulls the arrays. Not thread-safe -- only the aggregator thread calls these.
 */
public final class PropertyCardinalityHandler {

  private final int cardinalityLimit;
  private final CharSequence[] keys;
  private final UTF8BytesString[] values;
  private final int mask;

  private int size;
  private UTF8BytesString cacheBlocked;

  public PropertyCardinalityHandler(int cardinalityLimit) {
    this.cardinalityLimit = cardinalityLimit;
    int cap = tableSizeFor(cardinalityLimit);
    this.keys = new CharSequence[cap];
    this.values = new UTF8BytesString[cap];
    this.mask = cap - 1;
  }

  /**
   * Returns the canonical UTF8 form for {@code value}, or the {@code blocked_by_tracer} sentinel
   * when the budget is exhausted. The cap check runs before the lookup, so values that were
   * registered earlier in this cycle also collapse to the sentinel once the cap is hit.
   */
  public UTF8BytesString register(CharSequence value) {
    if (size >= cardinalityLimit) {
      return blockedByTracer();
    }
    int i = value.hashCode() & mask;
    while (true) {
      CharSequence k = keys[i];
      if (k == null) {
        UTF8BytesString newUtf8 = UTF8BytesString.create(value);
        keys[i] = value;
        values[i] = newUtf8;
        size++;
        return newUtf8;
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
    cached = UTF8BytesString.create("blocked_by_tracer");
    this.cacheBlocked = cached;
    return cached;
  }

  /**
   * Drops every value registered this cycle, refreshing the budget. The {@code blocked_by_tracer}
   * sentinel survives, so previously-issued sentinel references stay equal to fresh ones.
   */
  public void reset() {
    if (size > 0) {
      Arrays.fill(keys, null);
      Arrays.fill(values, null);
      size = 0;
    }
  }

  /** Power-of-two table size with at most ~50% load factor at full cardinality. */
  private static int tableSizeFor(int cardinalityLimit) {
    int target = Math.max(cardinalityLimit * 2, 16);
    return Integer.highestOneBit(target - 1) << 1;
  }
}

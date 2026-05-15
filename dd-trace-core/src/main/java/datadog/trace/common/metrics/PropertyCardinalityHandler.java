package datadog.trace.common.metrics;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.util.HashMap;

public final class PropertyCardinalityHandler {
  private final int cardinalityLimit;

  private final HashMap<CharSequence, UTF8BytesString> curUtf8s;

  private UTF8BytesString cacheBlocked = null;

  public PropertyCardinalityHandler(int cardinalityLimit) {
    this.cardinalityLimit = cardinalityLimit;

    // pre-sizing properly to avoid rehashing
    this.curUtf8s = new HashMap<>((int) Math.ceil(cardinalityLimit / 0.75) + 1);
  }

  public UTF8BytesString register(CharSequence value) {
    if (this.curUtf8s.size() >= this.cardinalityLimit) {
      return this.blockedByTracer();
    }

    UTF8BytesString existingUtf8 = this.curUtf8s.get(value);
    if (existingUtf8 != null) return existingUtf8;

    // TODO: maybe use a fallback cache to reduce allocations across reset cycles
    UTF8BytesString newUtf8 = UTF8BytesString.create(value);
    this.curUtf8s.put(value, newUtf8);
    return newUtf8;
  }

  private UTF8BytesString blockedByTracer() {
    UTF8BytesString cacheBlocked = this.cacheBlocked;
    if (cacheBlocked != null) return cacheBlocked;

    this.cacheBlocked = cacheBlocked = UTF8BytesString.create("blocked_by_tracer");
    return cacheBlocked;
  }

  public void reset() {
    this.curUtf8s.clear();
  }
}

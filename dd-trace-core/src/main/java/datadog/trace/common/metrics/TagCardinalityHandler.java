package datadog.trace.common.metrics;

import datadog.trace.api.TagMap;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.util.HashMap;

public final class TagCardinalityHandler {
  private final String tag;
  private final int cardinalityLimit;

  private final HashMap<Object, UTF8BytesString> curUtf8Pairs;

  private UTF8BytesString cacheBlocked = null;

  public TagCardinalityHandler(String tag, int cardinalityLimit) {
    this.tag = tag;
    this.cardinalityLimit = cardinalityLimit;

    // pre-sizing properly to avoid rehashing
    this.curUtf8Pairs = new HashMap<>((int) Math.ceil(cardinalityLimit / 0.75) + 1);
  }

  public UTF8BytesString register(TagMap.Entry entry) {
    if (this.curUtf8Pairs.size() >= this.cardinalityLimit) {
      return this.blockedByTracer();
    }

    if (!isValidType(entry)) {
      return this.blockedByTracer();
    }

    // NOTE: This could lead to boxing -- not ideal
    Object cacheKey = entry.objectValue();
    UTF8BytesString existing = this.curUtf8Pairs.get(cacheKey);
    if (existing != null) return existing;

    // TODO: maybe use a fallback cache to reduce allocations across reset cycles
    UTF8BytesString newPair = UTF8BytesString.create(this.tag + ":" + entry.stringValue());
    this.curUtf8Pairs.put(cacheKey, newPair);
    return newPair;
  }

  /**
   * String-keyed overload for callers that already hold a {@code (tag, value)} pair as Strings and
   * would rather not allocate a {@link TagMap.Entry} per lookup -- e.g. the metrics aggregator's
   * peer-tag flow, where peer-tag values are flattened into a {@code String[]} on the snapshot.
   */
  public UTF8BytesString register(String value) {
    if (this.curUtf8Pairs.size() >= this.cardinalityLimit) {
      return this.blockedByTracer();
    }

    UTF8BytesString existing = this.curUtf8Pairs.get(value);
    if (existing != null) return existing;

    UTF8BytesString newPair = UTF8BytesString.create(this.tag + ":" + value);
    this.curUtf8Pairs.put(value, newPair);
    return newPair;
  }

  private static final boolean isValidType(TagMap.Entry entry) {
    return entry.isNumericPrimitive() || entry.objectValue() instanceof CharSequence;
  }

  private UTF8BytesString blockedByTracer() {
    UTF8BytesString cacheBlocked = this.cacheBlocked;
    if (cacheBlocked != null) return cacheBlocked;

    this.cacheBlocked = cacheBlocked = UTF8BytesString.create(this.tag + ":blocked_by_tracer");
    return cacheBlocked;
  }

  public void reset() {
    this.curUtf8Pairs.clear();
  }
}

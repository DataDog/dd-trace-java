package datadog.trace.common.metrics;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.util.HashMap;

/**
 * Cardinality-capped UTF8 canonicalizer for one property field.
 *
 * <p>The type parameter {@code T} pins the input type per handler so the {@link HashMap} cache key
 * is a class with well-defined {@code equals}/{@code hashCode} (e.g. {@code String}) rather than
 * the abstract {@code CharSequence} interface, where {@code "foo".equals(UTF8BytesString
 * .create("foo"))} is {@code false}. Each call site uses the type its {@code SpanSnapshot} field
 * carries; the compiler then enforces type consistency across calls to a given handler.
 */
public final class PropertyCardinalityHandler<T extends CharSequence> {
  private final int cardinalityLimit;

  private final HashMap<T, UTF8BytesString> curUtf8s;

  private UTF8BytesString cacheBlocked = null;

  public PropertyCardinalityHandler(int cardinalityLimit) {
    this.cardinalityLimit = cardinalityLimit;

    // pre-sizing properly to avoid rehashing
    this.curUtf8s = new HashMap<>((int) Math.ceil(cardinalityLimit / 0.75) + 1);
  }

  public UTF8BytesString register(T value) {
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

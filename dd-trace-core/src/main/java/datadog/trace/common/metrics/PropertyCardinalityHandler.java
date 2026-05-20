package datadog.trace.common.metrics;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Cardinality-capped UTF8 canonicalizer for one property field.
 *
 * <p>The type parameter {@code T} pins the input type per handler so the cache key is a class with
 * well-defined {@code equals}/{@code hashCode} (e.g. {@code String}) rather than the abstract
 * {@code CharSequence} interface, where {@code "foo".equals(UTF8BytesString.create("foo"))} is
 * {@code false}. Each call site uses the type its {@code SpanSnapshot} field carries; the compiler
 * then enforces type consistency across calls to a given handler.
 *
 * <p>Two tiers of state:
 *
 * <ul>
 *   <li>{@link #seenThisCycle} -- values that have consumed a slot of the cardinality budget this
 *       reporting cycle. Cleared on {@link #reset()}.
 *   <li>{@link #utf8Cache} -- LRU-bounded reuse cache of previously-built {@link UTF8BytesString}
 *       instances. Survives {@code reset()}, so a value seen across multiple cycles canonicalizes
 *       to the same instance and avoids re-allocation. Bounded at {@code 2 * cardinalityLimit};
 *       once full, the eldest entry is evicted by {@link LinkedHashMap}'s access-order tracking.
 * </ul>
 *
 * <p>Reusing UTF8BytesString instances across cycles also benefits downstream identity-based
 * comparisons: equality short-circuits to {@code ==} when both sides came from the cache.
 */
public final class PropertyCardinalityHandler<T extends CharSequence> {
  /** Long-lived UTF8 cache holds this multiple of the per-cycle cardinality limit. */
  private static final int CACHE_MULTIPLIER = 2;

  private final int cardinalityLimit;

  /** Values that have consumed a slot of the cardinality budget this cycle. Cleared on reset. */
  private final HashSet<T> seenThisCycle;

  /**
   * LRU UTF8 cache; survives reset. Eviction handled by {@link LinkedHashMap#removeEldestEntry}.
   */
  private final LinkedHashMap<T, UTF8BytesString> utf8Cache;

  private UTF8BytesString cacheBlocked = null;

  public PropertyCardinalityHandler(int cardinalityLimit) {
    this.cardinalityLimit = cardinalityLimit;

    final int cacheLimit = cardinalityLimit * CACHE_MULTIPLIER;
    // pre-sizing properly to avoid rehashing
    this.seenThisCycle = new HashSet<>((int) Math.ceil(cardinalityLimit / 0.75) + 1);
    this.utf8Cache =
        new LinkedHashMap<T, UTF8BytesString>(
            (int) Math.ceil(cacheLimit / 0.75) + 1, 0.75f, true /* access-order */) {
          @Override
          protected boolean removeEldestEntry(Map.Entry<T, UTF8BytesString> eldest) {
            return size() > cacheLimit;
          }
        };
  }

  public UTF8BytesString register(T value) {
    // Cardinality budget: first-time-this-cycle values consume a slot; overflow returns sentinel.
    if (!this.seenThisCycle.contains(value)) {
      if (this.seenThisCycle.size() >= this.cardinalityLimit) {
        return this.blockedByTracer();
      }
      this.seenThisCycle.add(value);
    }

    // UTF8 lookup: long-lived cache reuses across cycles.
    UTF8BytesString cached = this.utf8Cache.get(value);
    if (cached != null) return cached;

    UTF8BytesString fresh = UTF8BytesString.create(value);
    this.utf8Cache.put(value, fresh);
    return fresh;
  }

  private UTF8BytesString blockedByTracer() {
    UTF8BytesString cacheBlocked = this.cacheBlocked;
    if (cacheBlocked != null) return cacheBlocked;

    this.cacheBlocked = cacheBlocked = UTF8BytesString.create("blocked_by_tracer");
    return cacheBlocked;
  }

  public void reset() {
    this.seenThisCycle.clear();
    // utf8Cache deliberately not cleared -- cross-cycle reuse is the point.
  }
}

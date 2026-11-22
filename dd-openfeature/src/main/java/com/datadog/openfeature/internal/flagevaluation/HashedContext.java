package com.datadog.openfeature.internal.flagevaluation;

import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * A copied evaluation context that keeps its aggregation hash up to date as entries are put, so the
 * aggregator, on the evaluating thread, doesn't walk the context a second time to hash it.
 *
 * <p>The hash is {@link ConcurrentFlagAggregator#contextHash}'s: independent of iteration order,
 * and distinguishing value types. Entries are combined by addition, so a replaced entry's share is
 * subtracted and the new one added.
 */
public final class HashedContext extends HashMap<String, Object> {
  private static final long serialVersionUID = 1L;

  private long entryHashes;

  /** The context's aggregation hash: what {@code contextHash} computes by walking it. */
  long contextHash() {
    return isEmpty() ? 0L : size() + entryHashes;
  }

  @Override
  public Object put(final String key, @Nullable final Object value) {
    final boolean replacing = containsKey(key);
    final Object previous = super.put(key, value);
    if (replacing) {
      entryHashes -= entryHash(key, previous);
    }
    entryHashes += entryHash(key, value);
    return previous;
  }

  @Override
  public void putAll(final Map<? extends String, ?> entries) {
    for (final Map.Entry<? extends String, ?> entry : entries.entrySet()) {
      put(entry.getKey(), entry.getValue());
    }
  }

  @Override
  public Object remove(final Object key) {
    if (!containsKey(key)) {
      return null;
    }
    final Object previous = super.remove(key);
    entryHashes -= entryHash((String) key, previous);
    return previous;
  }

  @Override
  public void clear() {
    super.clear();
    entryHashes = 0L;
  }

  /** One entry's share of the context hash. */
  static long entryHash(final String key, @Nullable final Object value) {
    final long valueHash =
        value == null ? 0L : (((long) value.getClass().hashCode()) << 32) ^ value.hashCode();
    return mix(key.hashCode() * 0x9E3779B97F4A7C15L + valueHash);
  }

  /** The finalizer of SplitMix64: spreads every input bit over the whole result. */
  private static long mix(long z) {
    z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
    z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
    return z ^ (z >>> 31);
  }
}

package datadog.trace.api.cache;

import datadog.trace.api.Function;

public interface DDCache<K, V> {
  /** Assigns key to value. Returns the previous value or null if no previous value existed */
  V put(final K key, V value);

  /** Returns the value assigned to this key or null if no such value exists */
  V getIfPresent(final K key);

  /** Computes a value for key if key has no assignment in this cache */
  V computeIfAbsent(final K key, Function<K, ? extends V> func);
}

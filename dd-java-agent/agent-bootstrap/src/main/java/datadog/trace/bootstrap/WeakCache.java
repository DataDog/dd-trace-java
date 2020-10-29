package datadog.trace.bootstrap;

import datadog.trace.api.Function;

public interface WeakCache<K, V> {
  interface Provider {
    <K, V> WeakCache<K, V> newWeakCache(long maxSize);
  }

  V getIfPresent(final K key);

  V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction);

  void put(final K key, final V value);
}

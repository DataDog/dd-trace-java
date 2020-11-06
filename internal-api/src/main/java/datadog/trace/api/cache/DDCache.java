package datadog.trace.api.cache;

import datadog.trace.api.Function;

public interface DDCache<K, V> {

  V computeIfAbsent(final K key, Function<K, ? extends V> func);
}

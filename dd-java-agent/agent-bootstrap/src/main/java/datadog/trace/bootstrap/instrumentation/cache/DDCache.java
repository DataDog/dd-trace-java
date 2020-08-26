package datadog.trace.bootstrap.instrumentation.cache;

import datadog.trace.bootstrap.instrumentation.api.Function;

public interface DDCache<K, V> {

  V computeIfAbsent(final K key, Function<K, ? extends V> func);
}

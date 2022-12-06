package datadog.trace.api.cache;

import java.util.function.Function;

public interface DDCache<K, V> {

  V computeIfAbsent(final K key, Function<K, ? extends V> func);

  void clear();
}

package datadog.trace.bootstrap.instrumentation.cache;

import datadog.trace.bootstrap.instrumentation.api.Function;
import java.util.concurrent.ConcurrentHashMap;

final class CHMCache<K, V> implements DDCache<K, V> {

  private final ConcurrentHashMap<K, V> chm;

  public CHMCache(final int capacity) {
    this.chm = new ConcurrentHashMap<>(capacity);
  }

  @Override
  public V computeIfAbsent(K key, Function<K, ? extends V> func) {
    if (null == key) {
      return null;
    }
    V value = chm.get(key);
    if (null == value) {
      value = func.apply(key);
      V winner = chm.putIfAbsent(key, value);
      if (null != winner) {
        value = winner;
      }
    }
    return value;
  }
}

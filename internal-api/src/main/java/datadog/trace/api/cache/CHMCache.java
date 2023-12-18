package datadog.trace.api.cache;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;

final class CHMCache<K, V> implements DDCache<K, V> {

  private final ConcurrentHashMap<K, V> chm;

  public CHMCache(final int initialCapacity) {
    this.chm = new ConcurrentHashMap<>(initialCapacity);
  }

  @Override
  public V computeIfAbsent(K key, Function<K, ? extends V> producer) {
    if (null == key) {
      return null;
    }
    V value = chm.get(key);
    if (null == value) {
      value = producer.apply(key);
      V winner = chm.putIfAbsent(key, value);
      if (null != winner) {
        value = winner;
      }
    }
    return value;
  }

  @Override
  public void clear() {
    chm.clear();
  }

  @Override
  public void visit(BiConsumer<K, V> consumer) {
    for (Map.Entry<K, V> e : chm.entrySet()) {
      consumer.accept(e.getKey(), e.getValue());
    }
  }
}

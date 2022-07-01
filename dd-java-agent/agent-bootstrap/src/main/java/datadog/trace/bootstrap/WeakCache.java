package datadog.trace.bootstrap;

import datadog.trace.api.function.Function;

public interface WeakCache<K, V> {
  V getIfPresent(final K key);

  V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction);

  void put(final K key, final V value);

  abstract class Supplier {
    private static volatile Supplier SUPPLIER;

    protected abstract <K, V> WeakCache<K, V> get(long maxSize);

    public static <K, V> WeakCache<K, V> newWeakCache(long maxSize) {
      return SUPPLIER.get(maxSize);
    }

    public static synchronized void registerIfAbsent(Supplier supplier) {
      if (null == SUPPLIER) {
        SUPPLIER = supplier;
      }
    }
  }
}

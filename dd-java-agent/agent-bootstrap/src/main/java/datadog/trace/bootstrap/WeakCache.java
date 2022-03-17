package datadog.trace.bootstrap;

import datadog.trace.api.Function;
import java.util.concurrent.atomic.AtomicReference;

public interface WeakCache<K, V> {
  V getIfPresent(final K key);

  V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction);

  void put(final K key, final V value);

  abstract class Supplier {
    private static final AtomicReference<Supplier> SUPPLIER = new AtomicReference<>();

    protected abstract <K, V> WeakCache<K, V> get(long maxSize);

    public static <K, V> WeakCache<K, V> newWeakCache(long maxSize) {
      return SUPPLIER.get().get(maxSize);
    }

    public static void registerIfAbsent(Supplier supplier) {
      SUPPLIER.compareAndSet(null, supplier);
    }
  }
}

package datadog.trace.bootstrap;

import datadog.trace.api.Function;
import java.util.concurrent.atomic.AtomicReference;

public interface WeakMap<K, V> {
  int size();

  boolean containsKey(K target);

  V get(K key);

  void put(K key, V value);

  void putIfAbsent(K key, V value);

  V computeIfAbsent(K key, Function<? super K, ? extends V> supplier);

  V remove(K key);

  abstract class Supplier {
    private static final AtomicReference<Supplier> SUPPLIER = new AtomicReference<>();

    protected abstract <K, V> WeakMap<K, V> get();

    public static <K, V> WeakMap<K, V> newWeakMap() {
      return SUPPLIER.get().get();
    }

    public static void registerIfAbsent(Supplier supplier) {
      SUPPLIER.compareAndSet(null, supplier);
    }
  }
}

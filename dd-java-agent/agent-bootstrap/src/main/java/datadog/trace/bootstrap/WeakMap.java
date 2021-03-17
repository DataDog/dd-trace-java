package datadog.trace.bootstrap;

import datadog.trace.api.Function;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

public interface WeakMap<K, V> {

  int size();

  boolean containsKey(K target);

  V get(K key);

  void put(K key, V value);

  void putIfAbsent(K key, V value);

  V computeIfAbsent(K key, Function<? super K, ? extends V> supplier);

  class Provider {
    private static final AtomicReferenceFieldUpdater<Provider, Implementation> UPDATER =
        AtomicReferenceFieldUpdater.newUpdater(Provider.class, Implementation.class, "provider");
    private volatile Implementation provider = null;
    private static final Provider INSTANCE = new Provider();

    public static void registerIfAbsent(final Implementation provider) {
      UPDATER.compareAndSet(INSTANCE, null, provider);
    }

    public static <K, V> WeakMap<K, V> newWeakMap() {
      return INSTANCE.provider.get();
    }
  }

  interface Implementation {
    <K, V> WeakMap<K, V> get();
  }
}

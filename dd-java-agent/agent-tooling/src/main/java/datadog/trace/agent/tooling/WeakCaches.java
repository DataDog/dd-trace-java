package datadog.trace.agent.tooling;

import com.blogspot.mydailyjava.weaklockfree.WeakConcurrentMap;
import com.googlecode.concurrentlinkedhashmap.ConcurrentLinkedHashMap;
import datadog.trace.api.function.Function;
import datadog.trace.bootstrap.WeakCache;
import java.util.concurrent.ConcurrentMap;

public class WeakCaches {
  private static final long DEFAULT_CACHE_CAPACITY = 32;

  public static <K, V> WeakCache<K, V> newWeakCache() {
    return newWeakCache(DEFAULT_CACHE_CAPACITY);
  }

  public static <K, V> WeakCache<K, V> newWeakCache(long maxSize) {
    return new Adapter<>(maxSize);
  }

  private WeakCaches() {}

  public static void registerAsSupplier() {
    WeakCache.Supplier.registerIfAbsent(
        new WeakCache.Supplier() {
          @Override
          protected <K, V> WeakCache<K, V> get(long maxSize) {
            return WeakCaches.newWeakCache(maxSize);
          }
        });
  }

  private static class Adapter<K, V> implements WeakCache<K, V> {
    private static final int CACHE_CONCURRENCY =
        Math.max(8, Runtime.getRuntime().availableProcessors());
    private final WeakConcurrentMap<K, V> weakMap;
    private final long maxSize;

    public Adapter(long maxSize) {
      // No parameterization because WeakKey isn't visible
      ConcurrentMap linkedMap =
          new ConcurrentLinkedHashMap.Builder()
              .maximumWeightedCapacity(maxSize)
              .concurrencyLevel(CACHE_CONCURRENCY)
              .build();

      weakMap = new WeakConcurrentMap<>(false, true, linkedMap);

      this.maxSize = maxSize;
    }

    @Override
    public V getIfPresent(K key) {
      return weakMap.getIfPresent(key);
    }

    @Override
    public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
      V value = weakMap.getIfPresent(key);
      if (value == null) {
        value = mappingFunction.apply(key);

        expungeIfNecessary();
        V oldValue = weakMap.putIfProbablyAbsent(key, value);
        if (oldValue != null) {
          value = oldValue;
        }
      }

      return value;
    }

    @Override
    public void put(K key, V value) {
      expungeIfNecessary();

      weakMap.put(key, value);
    }

    @Override
    public void clear() {
      weakMap.clear();
    }

    private void expungeIfNecessary() {
      if (weakMap.approximateSize() >= maxSize) {
        weakMap.expungeStaleEntries();
      }
    }
  }
}

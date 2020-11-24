package datadog.trace.agent.tooling;

import com.blogspot.mydailyjava.weaklockfree.WeakConcurrentMap;
import com.googlecode.concurrentlinkedhashmap.ConcurrentLinkedHashMap;
import datadog.trace.api.Function;
import datadog.trace.bootstrap.WeakCache;
import java.util.concurrent.ConcurrentMap;

public class CLHMWeakCache<K, V> implements WeakCache<K, V> {
  public static final class Provider implements WeakCache.Provider {
    @Override
    public <K, V> WeakCache<K, V> newWeakCache(long maxSize) {
      return new CLHMWeakCache<>(maxSize);
    }
  }

  private static final int CACHE_CONCURRENCY =
      Math.max(8, Runtime.getRuntime().availableProcessors());
  private final WeakConcurrentMap<K, V> weakMap;
  private final long maxSize;

  public CLHMWeakCache(long maxSize) {
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

  private void expungeIfNecessary() {
    if (weakMap.approximateSize() >= maxSize) {
      weakMap.expungeStaleEntries();
    }
  }
}

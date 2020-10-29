package datadog.trace.agent.tooling;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import datadog.trace.api.Function;
import datadog.trace.bootstrap.WeakCache;
import java.util.concurrent.TimeUnit;

public class CaffeineWeakCache<K, V> implements WeakCache<K, V> {
  public static final class Provider implements WeakCache.Provider {
    @Override
    public <K, V> WeakCache<K, V> newWeakCache(long maxSize) {
      return new CaffeineWeakCache<>(maxSize);
    }
  }

  private final Cache<K, V> cache;

  public CaffeineWeakCache(long maxSize) {
    cache =
        Caffeine.newBuilder()
            .weakKeys()
            .maximumSize(maxSize)
            .expireAfterAccess(10, TimeUnit.MINUTES)
            .build();
  }

  @Override
  public V getIfPresent(K key) {
    return cache.getIfPresent(key);
  }

  @Override
  public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
    // Unable to use cache.get() directly because it relies on java.util.function.Function which is
    // only available in Java8+.  This is less efficient.  The raciness is unimportant because this
    // is a cache
    V value = cache.getIfPresent(key);
    if (value == null) {
      value = mappingFunction.apply(key);

      cache.put(key, value);
    }
    return value;
  }

  @Override
  public void put(K key, V value) {
    cache.put(key, value);
  }
}

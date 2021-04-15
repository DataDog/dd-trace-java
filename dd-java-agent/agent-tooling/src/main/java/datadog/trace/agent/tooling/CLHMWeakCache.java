package datadog.trace.agent.tooling;

import com.googlecode.concurrentlinkedhashmap.ConcurrentLinkedHashMap;
import com.googlecode.concurrentlinkedhashmap.EvictionListener;
import datadog.trace.api.Function;
import datadog.trace.bootstrap.WeakCache;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;

public class CLHMWeakCache<K, V> extends ReferenceQueue<K> implements WeakCache<K, V> {
  public static final class Provider implements WeakCache.Provider {
    @Override
    public <K, V> WeakCache<K, V> newWeakCache(long maxSize) {
      return new CLHMWeakCache<>(maxSize);
    }
  }

  private static final int CACHE_CONCURRENCY =
      Math.max(8, Runtime.getRuntime().availableProcessors());
  private final ConcurrentLinkedHashMap<WeakKey<K>, V> concurrentMap;

  public CLHMWeakCache(long maxSize) {
    concurrentMap =
        new ConcurrentLinkedHashMap.Builder<WeakKey<K>, V>()
            .maximumWeightedCapacity(maxSize)
            .listener(
                new EvictionListener<WeakKey<K>, V>() {
                  @Override
                  public void onEviction(WeakKey<K> weakKey, V value) {
                    CLHMWeakCache.this.expungeStaleEntries();
                  }
                })
            .concurrencyLevel(CACHE_CONCURRENCY)
            .build();
  }

  @Override
  public V getIfPresent(K key) {
    WeakKey<K> weakKey = new WeakKey<>(key, null);
    V value = concurrentMap.get(weakKey);
    weakKey.clear();
    return value;
  }

  @Override
  public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
    WeakKey<K> weakKey = new WeakKey<>(key, this);
    V value = concurrentMap.get(weakKey);
    if (value == null) {
      value = mappingFunction.apply(key);
      V oldValue = concurrentMap.putIfAbsent(weakKey, value);
      if (oldValue != null) {
        value = oldValue;
        weakKey.clear();
      }
    } else {
      weakKey.clear();
    }

    return value;
  }

  @Override
  public void put(K key, V value) {
    concurrentMap.put(new WeakKey<>(key, this), value);
  }

  private void expungeStaleEntries() {
    Reference<?> reference;
    while ((reference = poll()) != null) {
      concurrentMap.remove(reference);
    }
  }

  @Override
  public String toString() {
    return "CLHMWeakCache{" + concurrentMap + '}';
  }

  private static final class WeakKey<T> extends WeakReference<T> {

    private final int hashCode;

    WeakKey(T key, ReferenceQueue<? super T> queue) {
      super(key, queue);
      hashCode = key.hashCode();
    }

    @Override
    public int hashCode() {
      return hashCode;
    }

    @Override
    public boolean equals(Object other) {
      if (other == this) {
        return true;
      }
      if (null == other) {
        return false;
      }
      T key = get();
      if (null == key) {
        return false;
      }
      Object otherKey;
      if (other instanceof WeakKey) {
        WeakKey<?> otherWeakKey = (WeakKey<?>) other;
        if (hashCode != otherWeakKey.hashCode) {
          return false;
        }
        otherKey = otherWeakKey.get();
        if (null == otherKey) {
          return false;
        }
      } else {
        otherKey = other;
      }
      return key.equals(otherKey);
    }

    @Override
    public String toString() {
      return "WeakKey{" + get() + "}";
    }
  }
}

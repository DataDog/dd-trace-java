package datadog.trace.api.cache;

import java.util.function.ToIntFunction;

public final class DDCaches {

  private DDCaches() {}

  /**
   * Creates a cache which cannot grow beyond a fixed capacity. Useful for caching relationships
   * between low cardinality but potentially unbounded keys with values, without risking using
   * unbounded space.
   *
   * <p>When using immutable array keys prefer {@link #newFixedSizeArrayKeyCache(int)}.
   *
   * @param capacity the cache's fixed capacity
   * @param <K> the key type
   * @param <V> the value type
   */
  public static <K, V> DDCache<K, V> newFixedSizeCache(final int capacity) {
    return new FixedSizeCache.ObjectHash<>(capacity);
  }

  /**
   * Specialized fixed-size cache that uses {@link System#identityHashCode} for key hashing and
   * equality.
   *
   * @see #newFixedSizeCache(int)
   */
  public static <K, V> DDCache<K, V> newFixedSizeIdentityCache(final int capacity) {
    return new FixedSizeCache.IdentityHash<>(capacity);
  }

  /**
   * Specialized fixed-size cache that uses {@link java.util.Arrays} for key hashing and equality.
   *
   * @see #newFixedSizeCache(int)
   */
  public static <K, V> DDCache<K[], V> newFixedSizeArrayKeyCache(final int capacity) {
    return new FixedSizeCache.ArrayHash<>(capacity);
  }

  /**
   * Specialized fixed-size cache whose keys are weakly referenced. Uses {@link
   * System#identityHashCode} for key hashing and equality.
   *
   * @see #newFixedSizeCache(int)
   */
  public static <K, V> DDCache<K, V> newFixedSizeWeakKeyCache(final int capacity) {
    return new FixedSizeWeakKeyCache<>(capacity);
  }

  /**
   * Specialized fixed-size cache which also tracks the overall weight of cached elements.
   *
   * @param capacity the cache's fixed capacity
   * @param weigher the weighing function used to weigh elements in the cache
   * @param maxWeight the maximum combined weight of all elements in the cache
   * @param <K> the key type
   * @param <V> the value type
   */
  public static <K, V> DDCache<K, V> newFixedSizeWeightedCache(
      final int capacity, final ToIntFunction<V> weigher, final int maxWeight) {
    return new FixedSizeWeightedCache<>(capacity, weigher, maxWeight);
  }

  /**
   * Creates a memoization of an association. Useful for creating an association between an
   * implicitly bounded set of keys and values, where the nature of the keys prevents unbounded
   * space usage.
   *
   * @param initialCapacity the initial capacity. To avoid resizing, should be larger than the total
   *     number of keys.
   * @param <K> the key type
   * @param <V> the value type
   * @return the value associated with the key
   */
  public static <K, V> DDCache<K, V> newUnboundedCache(final int initialCapacity) {
    return new CHMCache<>(initialCapacity);
  }

  public static <K, V> DDPartialKeyCache<K, V> newFixedSizePartialKeyCache(final int capacity) {
    return new FixedSizePartialKeyCache<>(capacity);
  }
}

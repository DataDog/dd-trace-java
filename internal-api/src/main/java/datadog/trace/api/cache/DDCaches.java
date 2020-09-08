package datadog.trace.api.cache;

public final class DDCaches {

  /**
   * Creates a cache which cannot grow beyond a fixed capacity. Useful for caching relationships
   * between low cardinality but potentially unbounded keys with values, without risking using
   * unbounded space.
   *
   * @param capacity the cache's fixed capacity
   * @param <K> the key type
   * @param <V> the value type
   * @return the value associated with the key
   */
  public static <K, V> DDCache<K, V> newFixedSizeCache(final int capacity) {
    return new FixedSizeCache<>(capacity);
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
}

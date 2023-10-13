package datadog.trace.api.cache;

/**
 * Cache that can work on parts of a key to look up a value.
 *
 * <p>An example would be looking up values from a substring of a string instead of first creating a
 * string to do the lookup.
 *
 * @param <K> key type
 * @param <V> value type
 */
public interface DDPartialKeyCache<K, V> {
  /**
   * Look up or create and store a value in the cache.
   *
   * @param key the key to look up
   * @param m extra parameter that is passed along with the key
   * @param n extra parameter that is passed along with the key
   * @param hasher how to compute the hash using key, m, and n
   * @param comparator how to compare the key, m, n, and value
   * @param producer how to create a cached value base on the key, m, and n if the lookup fails
   * @return the cached or created and stored value
   */
  V computeIfAbsent(
      final K key,
      int m,
      int n,
      Hasher<K> hasher,
      Comparator<K, V> comparator,
      Producer<K, ? extends V> producer);

  /** Clear the cache. */
  void clear();

  @FunctionalInterface
  interface Hasher<T> {
    int apply(T t, int m, int n);
  }

  @FunctionalInterface
  interface Comparator<T, U> {
    boolean test(T t, int m, int n, U u);
  }

  @FunctionalInterface
  interface Producer<T, R> {
    R apply(T t, int h, int m, int n);
  }
}

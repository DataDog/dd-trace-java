package datadog.trace.api.cache;

import java.util.function.BiConsumer;
import java.util.function.Function;

public interface DDCache<K, V> {
  /**
   * Look up or create and store a value in the cache.
   *
   * @param key the key to look up
   * @param producer how to create a cached value base on the key if the lookup fails
   * @return the cached or created and stored value
   */
  V computeIfAbsent(final K key, Function<K, ? extends V> producer);

  /** Clear the cache. */
  void clear();

  /** Visits elements currently in the cache; for debugging/triage purposes. */
  void visit(BiConsumer<K, V> consumer);
}

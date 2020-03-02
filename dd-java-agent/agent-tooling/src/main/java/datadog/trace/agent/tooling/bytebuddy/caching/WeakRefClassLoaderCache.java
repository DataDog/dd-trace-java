package datadog.trace.agent.tooling.bytebuddy.caching;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutionException;

public class WeakRefClassLoaderCache {
  static final int CONCURRENCY_LEVEL = 8;
  static final int LOADER_CAPACITY = 64;
  /**
   * Cache of recent ClassLoader WeakReferences; used to...
   *
   * <ul>
   *   <li>Reduced number of WeakReferences created
   *   <li>Allow for quick fast path equivalence check of composite keys
   * </ul>
   */
  final LoadingCache<ClassLoader, WeakReference<ClassLoader>> loaderRefCache =
      CacheBuilder.newBuilder()
          .weakKeys()
          .concurrencyLevel(CONCURRENCY_LEVEL)
          .initialCapacity(LOADER_CAPACITY / 2)
          .maximumSize(LOADER_CAPACITY)
          .build(
              new CacheLoader<ClassLoader, WeakReference<ClassLoader>>() {
                @Override
                public WeakReference<ClassLoader> load(final ClassLoader classLoader) {
                  return new WeakReference<>(classLoader);
                }
              });

  public WeakReference<ClassLoader> get(final ClassLoader classLoader) {
    try {
      return loaderRefCache.get(classLoader);
    } catch (final ExecutionException e) {
      return new WeakReference<>(classLoader);
    }
  }
}

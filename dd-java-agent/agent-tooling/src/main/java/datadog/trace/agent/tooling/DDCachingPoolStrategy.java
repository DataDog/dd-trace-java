package datadog.trace.agent.tooling;

import static datadog.trace.agent.tooling.ClassLoaderMatcher.BOOTSTRAP_CLASSLOADER;
import static net.bytebuddy.agent.builder.AgentBuilder.PoolStrategy;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;
import datadog.trace.bootstrap.WeakMap;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.pool.TypePool;

/**
 * Custom Pool strategy.
 *
 * <p>Here we are using WeakMap.Provider as the backing ClassLoader -> CacheProvider lookup.
 *
 * <p>We also use our bootstrap proxy when matching against the bootstrap loader.
 *
 * <p>The CacheProvider is also a custom implementation that uses guava's cache to evict.
 *
 * <p>By eviciting from the cache we are able to reduce the memory overhead of the agent for apps
 * that have many classes.
 *
 * <p>See eviction policy below.
 */
public class DDCachingPoolStrategy implements PoolStrategy {
  private static final WeakMap<ClassLoader, TypePool.CacheProvider> typePoolCache = WeakMap.Provider.newWeakMap();

  ScheduledExecutorService cleaner = Executors.newScheduledThreadPool(1);

  Runnable cleanupProcess = new Runnable() {
    @Override
    public void run() {
      clear();
    }
  };

  @Override
  public TypePool typePool(final ClassFileLocator classFileLocator, final ClassLoader classLoader) {
    final ClassLoader key =
        BOOTSTRAP_CLASSLOADER == classLoader ? Utils.getBootstrapProxy() : classLoader;
    TypePool.CacheProvider cache = typePoolCache.get(key);
    if (null == cache) {
      synchronized (key) {
        cache = typePoolCache.get(key);
        if (null == cache) {
          cache = EvictingCacheProvider.withObjectType();
          typePoolCache.put(key, cache);
        }
      }
    }
    return new TypePool.Default.WithLazyResolution(
        cache, classFileLocator, TypePool.Default.ReaderMode.FAST);
  }

  public void startCleanUpThread() {
    cleaner.scheduleAtFixedRate(cleanupProcess, 0, 30, TimeUnit.SECONDS);
  }

  public void shutdownCleanUpThread() {
    cleaner.shutdown();
  }

  public void clear() {
    Iterator<Map.Entry<ClassLoader, TypePool.CacheProvider>> iterator = typePoolCache.iterator();
    while(iterator.hasNext()) {
      Map.Entry<ClassLoader, TypePool.CacheProvider> next = iterator.next();
      next.getValue().clear();
    }
  }

  @Slf4j
  private static class EvictingCacheProvider implements TypePool.CacheProvider {

    /** A map containing all cached resolutions by their names. */
    private final Cache<String, TypePool.Resolution> cache;

    /** Creates a new simple cache. */
    private EvictingCacheProvider() {
      cache =
          CacheBuilder.newBuilder()
              .initialCapacity(100)
              .maximumSize(1000)
              .expireAfterAccess(1, TimeUnit.MINUTES)
              .build();
    }

    private static TypePool.CacheProvider withObjectType() {
      final TypePool.CacheProvider cacheProvider = new EvictingCacheProvider();
      cacheProvider.register(
          Object.class.getName(), new TypePool.Resolution.Simple(TypeDescription.OBJECT));
      return cacheProvider;
    }

    @Override
    public TypePool.Resolution find(final String name) {
      return cache.getIfPresent(name);
    }

    @Override
    public TypePool.Resolution register(final String name, final TypePool.Resolution resolution) {
      try {
        return cache.get(name, new ResolutionProvider(resolution));
      } catch (final ExecutionException e) {
        return resolution;
      }
    }

    @Override
    public void clear() {
      cache.invalidateAll();
      // Cache#invalidate() invalidates all the keys but do not actually remove them.
      cache.cleanUp();
    }

    private static class ResolutionProvider implements Callable<TypePool.Resolution> {
      private final TypePool.Resolution value;

      private ResolutionProvider(final TypePool.Resolution value) {
        this.value = value;
      }

      @Override
      public TypePool.Resolution call() {
        return value;
      }
    }
  }
}

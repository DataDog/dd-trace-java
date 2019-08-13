package datadog.trace.agent.tooling;

import static datadog.trace.agent.tooling.ClassLoaderMatcher.BOOTSTRAP_CLASSLOADER;
import static net.bytebuddy.agent.builder.AgentBuilder.PoolStrategy;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableMap;
import datadog.trace.bootstrap.WeakMap;

import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
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
  private static final WeakMap<ClassLoader, TypePool.CacheProvider> typePoolCache =
      WeakMap.Provider.newWeakMap();

  /** Cache expiration timeout. */
  private int cacheExpireTimeout;

  /** Cache clean-up interval. */
  private int cacheCleanupInterval;

  private TimeUnit timeUnit;

  public DDCachingPoolStrategy() {
    // By default both cache expiration time and clean up thread interval are set to 60 seconds.
    this(1, 1, TimeUnit.MINUTES);
  }

  public DDCachingPoolStrategy(
      int cacheExpireTimeout, int cacheCleanupInterval, TimeUnit timeUnit) {
    this.cacheExpireTimeout = cacheExpireTimeout;
    this.cacheCleanupInterval = cacheCleanupInterval;
    this.timeUnit = timeUnit;
  }

  private ScheduledExecutorService cleaner =
      Executors.newScheduledThreadPool(
          1,
          new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
              Thread thread = new Thread(r);
              thread.setDaemon(true);
              thread.setName("dd-cache-pool-cleaner");
              return thread;
            }
          });

  private Runnable cleanupProcess =
      new Runnable() {
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
          cache = EvictingCacheProvider.withObjectType(this.cacheExpireTimeout, this.timeUnit);
          typePoolCache.put(key, cache);
        }
      }
    }
    return new TypePool.Default.WithLazyResolution(
        cache, classFileLocator, TypePool.Default.ReaderMode.FAST);
  }

  public void startCleanUpThread() {
    cleaner.scheduleAtFixedRate(cleanupProcess, 0, this.cacheCleanupInterval, this.timeUnit);
  }

  public void clear() {
    Iterator<Map.Entry<ClassLoader, TypePool.CacheProvider>> iterator = typePoolCache.iterator();
    while (iterator.hasNext()) {
      Map.Entry<ClassLoader, TypePool.CacheProvider> next = iterator.next();
      if (next.getValue() instanceof EvictingCacheProvider) {
        ((EvictingCacheProvider) next.getValue()).cleanUpExpired();
      }
    }
  }

  @Slf4j
  private static class EvictingCacheProvider implements TypePool.CacheProvider {

    /** A map containing all cached resolutions by their names. */
    private final Cache<String, TypePool.Resolution> cache;

    /** Creates a new simple cache. */
    private EvictingCacheProvider(int cacheExpireSeconds, TimeUnit timeUnit) {
      cache =
          CacheBuilder.newBuilder()
              .initialCapacity(100)
              .maximumSize(1000)
              .expireAfterAccess(cacheExpireSeconds, timeUnit)
              .build();
    }

    private static TypePool.CacheProvider withObjectType(
        int cacheExpireSeconds, TimeUnit timeUnit) {
      final TypePool.CacheProvider cacheProvider =
          new EvictingCacheProvider(cacheExpireSeconds, timeUnit);
      cacheProvider.register(
          Object.class.getName(), new TypePool.Resolution.Simple(TypeDescription.OBJECT));
      return cacheProvider;
    }

    @Override
    public TypePool.Resolution find(final String name) {
      TypePool.Resolution found = cache.getIfPresent(name);
      System.out.format("Looking for %s: %s\n", name, found);
      return found;
    }

    @Override
    public TypePool.Resolution register(final String name, final TypePool.Resolution resolution) {
      System.out.format("Registering %s: %s\n", name, resolution);
      try {
        TypePool.Resolution resolution1 = cache.get(name, new ResolutionProvider(resolution));
        ImmutableMap<String, TypePool.Resolution> allPresent = cache.getAllPresent(Arrays.asList(name));
        return resolution1;
      } catch (final ExecutionException e) {
        return resolution;
      }
    }

    @Override
    public void clear() {
      cache.invalidateAll();
      // Cache#invalidate() invalidates all the keys but do not actually remove them.
      this.cleanUpExpired();
    }

    /** Cleanup from cache only expired items. */
    public void cleanUpExpired() {
      cache.cleanUp();
    }

    private static class ResolutionProvider implements Callable<TypePool.Resolution> {
      private final TypePool.Resolution value;

      private ResolutionProvider(final TypePool.Resolution value) {
        this.value = value;
      }

      @Override
      public TypePool.Resolution call() {
        System.out.printf("Not found so returning %s\n", value);
        return value;
      }
    }
  }
}

package datadog.trace.agent.tooling;

import static net.bytebuddy.agent.builder.AgentBuilder.*;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.pool.TypePool;

public class DDCachingPoolStrategy implements PoolStrategy {
  private static final Map<ClassLoader, TypePool.CacheProvider> typePoolCache =
      Collections.synchronizedMap(new WeakHashMap<ClassLoader, TypePool.CacheProvider>());

  @Override
  public TypePool typePool(ClassFileLocator classFileLocator, ClassLoader classLoader) {
    final ClassLoader key = null == classLoader ? Utils.getBootstrapProxy() : classLoader;
    TypePool.CacheProvider cache = typePoolCache.get(key);
    if (null == cache) {
      synchronized (key) {
        cache = typePoolCache.get(key);
        if (null == cache) {
          cache = TypePool.CacheProvider.Simple.withObjectType();
          typePoolCache.put(key, cache);
        }
      }
    }
    return new TypePool.Default.WithLazyResolution(
        cache, classFileLocator, TypePool.Default.ReaderMode.FAST);
  }
}

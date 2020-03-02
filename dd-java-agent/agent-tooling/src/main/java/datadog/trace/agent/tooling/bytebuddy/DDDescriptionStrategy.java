package datadog.trace.agent.tooling.bytebuddy;

import datadog.trace.agent.tooling.bytebuddy.caching.WeakRefClassLoaderCache;
import java.lang.ref.WeakReference;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.pool.TypePool;
import net.bytebuddy.utility.JavaModule;

public class DDDescriptionStrategy implements AgentBuilder.DescriptionStrategy {
  static final int CONCURRENCY_LEVEL = 8;
  static final int TYPE_INFO_CAPACITY = 64;

  private final WeakRefClassLoaderCache loaderRefCache;

  static final int BOOTSTRAP_HASH = 7236344; // Just a random number
  static final WeakReference<ClassLoader> BOOTSTRAP_REF = new WeakReference<>(null);

  public DDDescriptionStrategy(final WeakRefClassLoaderCache loaderRefCache) {
    this.loaderRefCache = loaderRefCache;
  }

  @Override
  public boolean isLoadedFirst() {
    return false;
  }

  @Override
  public TypeDescription apply(
      final String typeName,
      final Class<?> type,
      final TypePool typePool,
      final AgentBuilder.CircularityLock circularityLock,
      final ClassLoader classLoader,
      final JavaModule module) {
    if (classLoader == null) {
      return new TypeDescriptionWithTypeCacheKey(
          BOOTSTRAP_HASH, BOOTSTRAP_REF, typeName, typePool.describe(typeName).resolve());
    }
    return new TypeDescriptionWithTypeCacheKey(
        classLoader.hashCode(),
        loaderRefCache.get(classLoader),
        typeName,
        typePool.describe(typeName).resolve());
  }

  public static class TypeDescriptionWithTypeCacheKey
      extends TypeDescription.AbstractBase.OfSimpleType.WithDelegation {
    private final DDCachingPoolStrategy.TypeCacheKey.Partial partialKey;
    private final String typeName;
    private final TypeDescription delegate;

    public TypeDescriptionWithTypeCacheKey(
        final int classLoaderHashCode,
        final WeakReference<ClassLoader> classLoaderWeakReference,
        final String typeName,
        final TypeDescription delegate) {
      partialKey =
          new DDCachingPoolStrategy.TypeCacheKey.Partial(
              classLoaderHashCode, classLoaderWeakReference);
      this.typeName = typeName;
      this.delegate = delegate;
    }

    @Override
    protected TypeDescription delegate() {
      return delegate;
    }

    @Override
    public String getName() {
      return typeName;
    }

    public DDCachingPoolStrategy.TypeCacheKey.Partial getPartialKey() {
      return partialKey;
    }
  }
}

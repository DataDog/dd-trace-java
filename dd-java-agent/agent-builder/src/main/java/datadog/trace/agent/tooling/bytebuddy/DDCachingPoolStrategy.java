package datadog.trace.agent.tooling.bytebuddy;

import static datadog.trace.agent.tooling.bytebuddy.ClassFileLocators.classFileLocator;
import static datadog.trace.bootstrap.AgentClassLoading.LOCATING_CLASS;

import com.googlecode.concurrentlinkedhashmap.ConcurrentLinkedHashMap;
import datadog.trace.agent.tooling.WeakCaches;
import datadog.trace.api.Config;
import datadog.trace.api.function.Function;
import datadog.trace.bootstrap.WeakCache;
import java.lang.ref.WeakReference;
import java.util.concurrent.ConcurrentMap;
import net.bytebuddy.description.annotation.AnnotationList;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.description.type.TypeList;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.pool.TypePool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NEW (Jan 2020) Custom Pool strategy.
 *
 * <ul>
 *   Uses a Guava Cache directly...
 *   <li>better control over locking than WeakMap.Provider
 *   <li>provides direct control over concurrency level
 *   <li>initial and maximum capacity
 * </ul>
 *
 * <ul>
 *   There two core parts to the cache...
 *   <li>a cache of ClassLoader to WeakReference&lt;ClassLoader&gt;
 *   <li>a single cache of TypeResolutions for all ClassLoaders - keyed by a custom composite key of
 *       ClassLoader & class name
 * </ul>
 *
 * <p>This design was chosen to create a single limited size cache that can be adjusted for the
 * entire application -- without having to create a large number of WeakReference objects.
 *
 * <p>Eviction is handled almost entirely through a size restriction; however, softValues are still
 * used as a further safeguard.
 */
public class DDCachingPoolStrategy implements SharedTypePools.Supplier {
  private static final Logger log = LoggerFactory.getLogger(DDCachingPoolStrategy.class);
  // Many things are package visible for testing purposes --
  // others to avoid creation of synthetic accessors

  static final int CONCURRENCY_LEVEL = 8;
  static final int LOADER_CAPACITY = 64;
  static final int TYPE_CAPACITY = Config.get().getResolverTypePoolSize();

  static final int BOOTSTRAP_HASH = 7236344; // Just a random number

  private static final Function<ClassLoader, WeakReference<ClassLoader>> WEAK_REF =
      new Function<ClassLoader, WeakReference<ClassLoader>>() {
        @Override
        public WeakReference<ClassLoader> apply(ClassLoader input) {
          return new WeakReference<>(input);
        }
      };

  public static final DDCachingPoolStrategy INSTANCE =
      new DDCachingPoolStrategy(Config.get().isResolverUseLoadClassEnabled());

  public static void registerAsSupplier() {
    SharedTypePools.registerIfAbsent(INSTANCE);
  }

  /**
   * Cache of recent ClassLoader WeakReferences; used to...
   *
   * <ul>
   *   <li>Reduced number of WeakReferences created
   *   <li>Allow for quick fast path equivalence check of composite keys
   * </ul>
   */
  final WeakCache<ClassLoader, WeakReference<ClassLoader>> loaderRefCache =
      WeakCaches.newWeakCache(LOADER_CAPACITY);

  /**
   * Single shared Type.Resolution cache -- uses a composite key -- conceptually of loader & name
   */
  final ConcurrentMap<TypeCacheKey, TypePool.Resolution> sharedResolutionCache =
      new ConcurrentLinkedHashMap.Builder<TypeCacheKey, TypePool.Resolution>()
          .maximumWeightedCapacity(TYPE_CAPACITY)
          .concurrencyLevel(CONCURRENCY_LEVEL)
          .build();

  /** Fast path for bootstrap */
  final SharedResolutionCacheAdapter bootstrapCacheProvider;

  private final boolean fallBackToLoadClass;

  // visible for testing
  DDCachingPoolStrategy() {
    this(true);
  }

  private DDCachingPoolStrategy(boolean fallBackToLoadClass) {
    this.fallBackToLoadClass = fallBackToLoadClass;
    bootstrapCacheProvider =
        new SharedResolutionCacheAdapter(
            BOOTSTRAP_HASH, null, sharedResolutionCache, fallBackToLoadClass);
  }

  public final TypePool typePool(
      final ClassFileLocator classFileLocator, final ClassLoader classLoader) {
    if (classLoader == null) {
      return createCachingTypePool(bootstrapCacheProvider, classFileLocator);
    }

    WeakReference<ClassLoader> loaderRef = loaderRefCache.computeIfAbsent(classLoader, WEAK_REF);

    final int loaderHash = classLoader.hashCode();
    return createCachingTypePool(loaderHash, loaderRef, classFileLocator);
  }

  @Override
  public TypePool typePool(ClassLoader classLoader) {
    return typePool(classFileLocator(classLoader), classLoader);
  }

  @Override
  public void cacheAnnotationForMatching(String name) {}

  @Override
  public void beginInstall() {}

  @Override
  public void endInstall() {}

  @Override
  public void beginTransform() {}

  @Override
  public void endTransform() {}

  private TypePool.CacheProvider createCacheProvider(
      final int loaderHash, final WeakReference<ClassLoader> loaderRef) {
    return new SharedResolutionCacheAdapter(
        loaderHash, loaderRef, sharedResolutionCache, fallBackToLoadClass);
  }

  private TypePool createCachingTypePool(
      final int loaderHash,
      final WeakReference<ClassLoader> loaderRef,
      final ClassFileLocator classFileLocator) {
    return new TypePool.Default.WithLazyResolution(
        createCacheProvider(loaderHash, loaderRef),
        classFileLocator,
        TypePool.Default.ReaderMode.FAST);
  }

  private TypePool createCachingTypePool(
      final TypePool.CacheProvider cacheProvider, final ClassFileLocator classFileLocator) {
    return new TypePool.Default.WithLazyResolution(
        cacheProvider, classFileLocator, TypePool.Default.ReaderMode.FAST);
  }

  final long approximateSize() {
    return sharedResolutionCache.size();
  }

  /**
   * TypeCacheKey is key for the sharedResolutionCache. Conceptually, it is a mix of ClassLoader &
   * class name.
   *
   * <p>For efficiency & GC purposes, it is actually composed of loaderHash &
   * WeakReference&lt;ClassLoader&gt;
   *
   * <p>The loaderHash exists to avoid calling get & strengthening the Reference.
   */
  static final class TypeCacheKey {
    private final int loaderHash;
    private final WeakReference<ClassLoader> loaderRef;
    private final String className;

    private final int hashCode;

    TypeCacheKey(
        final int loaderHash, final WeakReference<ClassLoader> loaderRef, final String className) {
      this.loaderHash = loaderHash;
      this.loaderRef = loaderRef;
      this.className = className;

      hashCode = 31 * this.loaderHash + className.hashCode();
    }

    @Override
    public final int hashCode() {
      return hashCode;
    }

    @Override
    public boolean equals(final Object obj) {
      if (!(obj instanceof TypeCacheKey)) {
        return false;
      }

      final TypeCacheKey that = (TypeCacheKey) obj;

      if (loaderHash != that.loaderHash) {
        return false;
      }

      if (className.equals(that.className)) {
        // Fastpath loaderRef equivalence -- works because of WeakReference cache used
        // Also covers the bootstrap null loaderRef case
        if (loaderRef == that.loaderRef) {
          return true;
        }

        // need to perform a deeper loader check -- requires calling Reference.get
        // which can strengthen the Reference, so deliberately done last

        // If either reference has gone null, they aren't considered equivalent
        // Technically, this is a bit of violation of equals semantics, since
        // two equivalent references can become not equivalent.

        // In this case, it is fine because that means the ClassLoader is no
        // longer live, so the entries will never match anyway and will fall
        // out of the cache.
        final ClassLoader thisLoader = loaderRef.get();
        if (thisLoader == null) {
          return false;
        }

        final ClassLoader thatLoader = that.loaderRef.get();
        if (thatLoader == null) {
          return false;
        }

        return (thisLoader == thatLoader);
      } else {
        return false;
      }
    }
  }

  static final class SharedResolutionCacheAdapter implements TypePool.CacheProvider {
    private static final String OBJECT_NAME = "java.lang.Object";
    private static final TypePool.Resolution OBJECT_RESOLUTION =
        new TypePool.Resolution.Simple(new CachingTypeDescription(TypeDescription.OBJECT));

    private final int loaderHash;
    private final WeakReference<ClassLoader> loaderRef;
    private final ConcurrentMap<TypeCacheKey, TypePool.Resolution> sharedResolutionCache;
    private final boolean fallBackToLoadClass;

    SharedResolutionCacheAdapter(
        final int loaderHash,
        final WeakReference<ClassLoader> loaderRef,
        final ConcurrentMap<TypeCacheKey, TypePool.Resolution> sharedResolutionCache,
        final boolean fallBackToLoadClass) {
      this.loaderHash = loaderHash;
      this.loaderRef = loaderRef;
      this.sharedResolutionCache = sharedResolutionCache;
      this.fallBackToLoadClass = fallBackToLoadClass;
    }

    @Override
    public TypePool.Resolution find(final String className) {
      final TypePool.Resolution existingResolution =
          sharedResolutionCache.get(new TypeCacheKey(loaderHash, loaderRef, className));
      if (existingResolution != null) {
        return existingResolution;
      }

      if (OBJECT_NAME.equals(className)) {
        return OBJECT_RESOLUTION;
      }

      return null;
    }

    @Override
    public TypePool.Resolution register(final String className, TypePool.Resolution resolution) {
      if (OBJECT_NAME.equals(className)) {
        return resolution;
      }

      if (fallBackToLoadClass && resolution instanceof TypePool.Resolution.Illegal) {
        // If the normal pool only resolution have failed then fall back to creating the type
        // description from a loaded type by trying to load the class. This case is very rare and is
        // here to handle classes that are injected directly via calls to defineClass without
        // providing a way to get the class bytes.
        resolution = new CachingResolutionForMaybeLoadableType(loaderRef, className);
      } else {
        resolution = new CachingResolution(resolution);
      }

      sharedResolutionCache.put(new TypeCacheKey(loaderHash, loaderRef, className), resolution);
      return resolution;
    }

    @Override
    public void clear() {
      // Allowing the high-level eviction policy make the clearing decisions
    }
  }

  private static class CachingResolutionForMaybeLoadableType implements TypePool.Resolution {
    private final WeakReference<ClassLoader> loaderRef;
    private final String className;
    private volatile TypeDescription typeDescription = null;
    private volatile boolean isResolved = false;

    public CachingResolutionForMaybeLoadableType(
        WeakReference<ClassLoader> loaderRef, String className) {
      this.loaderRef = loaderRef;
      this.className = className;
    }

    @Override
    public boolean isResolved() {
      return isResolved;
    }

    @Override
    public TypeDescription resolve() {
      // Intentionally not "thread safe". Duplicate work deemed an acceptable trade-off.
      if (!isResolved) {
        Class<?> klass = null;
        ClassLoader classLoader = null;
        LOCATING_CLASS.begin();
        try {
          // Please note that by doing a loadClass, the type we are resolving will bypass
          // transformation since we are in the middle of a transformation. This should
          // be a very rare occurrence and not affect any classes we want to instrument.
          if (loaderRef != null) {
            classLoader = loaderRef.get();
            if (classLoader != null) {
              klass = classLoader.loadClass(className);
            } else {
              // classloader has been unloaded
            }
          } else { // bootstrap type resolution
            klass = Class.forName(className, false, null);
          }
        } catch (Throwable ignored) {
        } finally {
          LOCATING_CLASS.end();
        }
        if (klass != null) {
          // We managed to load the class
          typeDescription = TypeDescription.ForLoadedType.of(klass);
          log.debug(
              "Direct loadClass type resolution of {} from class loader {} bypass transformation",
              className,
              classLoader);
        }
        isResolved = true;
      }
      if (typeDescription == null) {
        throw new IllegalStateException("Cannot resolve type description for " + className);
      }
      return typeDescription;
    }
  }

  private static class CachingResolution implements TypePool.Resolution {
    private final TypePool.Resolution delegate;
    private TypeDescription cachedResolution;

    public CachingResolution(final TypePool.Resolution delegate) {
      this.delegate = delegate;
    }

    @Override
    public boolean isResolved() {
      return delegate.isResolved();
    }

    @Override
    public TypeDescription resolve() {
      // Intentionally not "thread safe". Duplicate work deemed an acceptable trade-off.
      if (cachedResolution == null) {
        cachedResolution = new CachingTypeDescription(delegate.resolve());
      }
      return cachedResolution;
    }
  }

  /**
   * TypeDescription implementation that delegates and caches the results for the expensive calls
   * commonly used by our instrumentation.
   */
  private static class CachingTypeDescription
      extends TypeDescription.AbstractBase.OfSimpleType.WithDelegation {
    private final TypeDescription delegate;

    // These fields are intentionally not "thread safe".
    // Duplicate work deemed an acceptable trade-off.
    private Generic superClass;
    private TypeList.Generic interfaces;
    private AnnotationList annotations;
    private MethodList<MethodDescription.InDefinedShape> methods;

    public CachingTypeDescription(final TypeDescription delegate) {
      this.delegate = delegate;
    }

    @Override
    protected TypeDescription delegate() {
      return delegate;
    }

    @Override
    public Generic getSuperClass() {
      if (superClass == null) {
        superClass = delegate.getSuperClass();
      }
      return superClass;
    }

    @Override
    public TypeList.Generic getInterfaces() {
      if (interfaces == null) {
        interfaces = delegate.getInterfaces();
      }
      return interfaces;
    }

    @Override
    public AnnotationList getDeclaredAnnotations() {
      if (annotations == null) {
        annotations = delegate.getDeclaredAnnotations();
      }
      return annotations;
    }

    @Override
    public MethodList<MethodDescription.InDefinedShape> getDeclaredMethods() {
      if (methods == null) {
        methods = delegate.getDeclaredMethods();
      }
      return methods;
    }

    @Override
    public String getName() {
      return delegate.getName();
    }
  }
}

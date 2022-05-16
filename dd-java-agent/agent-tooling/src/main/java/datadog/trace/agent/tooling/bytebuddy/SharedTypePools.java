package datadog.trace.agent.tooling.bytebuddy;

import java.util.concurrent.atomic.AtomicReference;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.pool.TypePool;

/** Manages {@link TypePool} use across muzzle and instrumentations. */
public final class SharedTypePools {
  private static final AtomicReference<Supplier> SUPPLIER = new AtomicReference<>();

  public static TypePool typePool(ClassLoader classLoader) {
    return typePool(ClassFileLocators.classFileLocator(classLoader), classLoader);
  }

  public static TypePool typePool(ClassFileLocator classFileLocator, ClassLoader classLoader) {
    return SUPPLIER.get().typePool(classFileLocator, classLoader);
  }

  public static void registerIfAbsent(Supplier supplier) {
    SUPPLIER.compareAndSet(null, supplier);
  }

  public interface Supplier {
    TypePool typePool(ClassFileLocator classFileLocator, ClassLoader classLoader);
  }

  /** Simple soft-cache for use during the build when testing or validating muzzle ranges. */
  public static Supplier simpleCache() {
    return new SharedTypePools.Supplier() {
      @Override
      public TypePool typePool(ClassFileLocator classFileLocator, ClassLoader classLoader) {
        return new TypePool.Default.WithLazyResolution(
            new TypePool.CacheProvider.Simple.UsingSoftReference(),
            classFileLocator,
            TypePool.Default.ReaderMode.FAST);
      }
    };
  }

  private SharedTypePools() {}
}

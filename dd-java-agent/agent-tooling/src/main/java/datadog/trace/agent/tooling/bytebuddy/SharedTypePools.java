package datadog.trace.agent.tooling.bytebuddy;

import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.pool.TypePool;

/** Pluggable {@link TypePool}s for use with instrumentation matching and muzzle checks. */
public final class SharedTypePools {
  private static volatile Supplier SUPPLIER;

  public static TypePool typePool(ClassLoader classLoader) {
    return SUPPLIER.typePool(ClassFileLocators.classFileLocator(classLoader), classLoader);
  }

  public static TypePool typePool(ClassFileLocator classFileLocator, ClassLoader classLoader) {
    return SUPPLIER.typePool(classFileLocator, classLoader);
  }

  public static synchronized void registerIfAbsent(Supplier supplier) {
    if (null == SUPPLIER) {
      SUPPLIER = supplier;
    }
  }

  public interface Supplier {
    TypePool typePool(ClassFileLocator classFileLocator, ClassLoader classLoader);
  }

  /** Simple cache for use during the build when testing or validating muzzle ranges. */
  public static Supplier simpleCache() {
    return new SharedTypePools.Supplier() {
      @Override
      public TypePool typePool(ClassFileLocator classFileLocator, ClassLoader unused) {
        return TypePool.Default.WithLazyResolution.of(classFileLocator);
      }
    };
  }

  private SharedTypePools() {}
}

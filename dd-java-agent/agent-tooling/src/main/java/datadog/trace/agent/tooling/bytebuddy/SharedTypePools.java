package datadog.trace.agent.tooling.bytebuddy;

import static datadog.trace.agent.tooling.bytebuddy.ClassFileLocators.classFileLocator;

import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.pool.TypePool;

/** Pluggable {@link TypePool}s for use with instrumentation matching and muzzle checks. */
public final class SharedTypePools {
  private static volatile Supplier SUPPLIER;

  public static TypePool typePool(ClassFileLocator classFileLocator, ClassLoader classLoader) {
    return SUPPLIER.typePool(classFileLocator, classLoader);
  }

  public static TypePool typePool(ClassLoader classLoader) {
    return SUPPLIER.typePool(classLoader);
  }

  public static void cacheAnnotationForMatching(String name) {
    SUPPLIER.cacheAnnotationForMatching(name);
  }

  public static void beginInstall() {
    SUPPLIER.beginInstall();
  }

  public static void endInstall() {
    SUPPLIER.endInstall();
  }

  public static void beginTransform() {
    SUPPLIER.beginTransform();
  }

  public static void endTransform() {
    SUPPLIER.endTransform();
  }

  public static synchronized void registerIfAbsent(Supplier supplier) {
    if (null == SUPPLIER) {
      SUPPLIER = supplier;
    }
  }

  public interface Supplier {
    TypePool typePool(ClassFileLocator classFileLocator, ClassLoader classLoader);

    TypePool typePool(ClassLoader classLoader);

    void cacheAnnotationForMatching(String name);

    void beginInstall();

    void endInstall();

    void beginTransform();

    void endTransform();
  }

  /** Simple cache for use during the build when testing or validating muzzle ranges. */
  public static Supplier simpleCache() {
    return new SharedTypePools.Supplier() {
      @Override
      public TypePool typePool(ClassFileLocator classFileLocator, ClassLoader unused) {
        return TypePool.Default.WithLazyResolution.of(classFileLocator);
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
    };
  }

  private SharedTypePools() {}
}

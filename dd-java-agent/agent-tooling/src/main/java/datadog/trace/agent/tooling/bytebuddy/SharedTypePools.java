package datadog.trace.agent.tooling.bytebuddy;

import static datadog.trace.agent.tooling.bytebuddy.ClassFileLocators.classFileLocator;

import java.lang.instrument.ClassFileTransformer;
import net.bytebuddy.pool.TypePool;

/** Pluggable {@link TypePool}s for use with instrumentation matching and muzzle checks. */
public final class SharedTypePools {
  private static volatile Supplier SUPPLIER;

  /** Retrieves the shared type-pool for the given class-loader. */
  public static TypePool typePool(ClassLoader classLoader) {
    return SUPPLIER.typePool(classLoader);
  }

  /** Hints that the given annotation is of interest and should be proactively cached. */
  public static void annotationOfInterest(String name) {
    SUPPLIER.annotationOfInterest(name);
  }

  /** Hints that the given annotations are of interest and should be proactively cached. */
  public static void annotationsOfInterest(Iterable<String> names) {
    for (String name : names) {
      SUPPLIER.annotationOfInterest(name);
    }
  }

  /** Hints that the javaagent has finished installing as a {@link ClassFileTransformer}. */
  public static void endInstall() {
    SUPPLIER.endInstall();
  }

  /** Hints that the javaagent has finished calling {@link ClassFileTransformer#transform}. */
  public static void endTransform() {
    SUPPLIER.endTransform();
  }

  public static synchronized void registerIfAbsent(Supplier supplier) {
    if (null == SUPPLIER) {
      SUPPLIER = supplier;
    }
  }

  public interface Supplier {
    /** Retrieves the shared type-pool for the given class-loader. */
    TypePool typePool(ClassLoader classLoader);

    /** Hints that the given annotation is of interest and should be proactively cached. */
    void annotationOfInterest(String name);

    /** Hints that the javaagent has finished installing as a {@link ClassFileTransformer}. */
    void endInstall();

    /** Hints that the javaagent has finished calling {@link ClassFileTransformer#transform}. */
    void endTransform();
  }

  /** Simple cache for use during the build when testing or validating muzzle ranges. */
  public static Supplier simpleCache() {
    return new SharedTypePools.Supplier() {
      @Override
      public TypePool typePool(ClassLoader classLoader) {
        return TypePool.Default.WithLazyResolution.of(classFileLocator(classLoader));
      }

      @Override
      public void annotationOfInterest(String name) {}

      @Override
      public void endInstall() {}

      @Override
      public void endTransform() {}
    };
  }

  private SharedTypePools() {}
}

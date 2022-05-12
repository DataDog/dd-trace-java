package datadog.trace.agent.tooling.bytebuddy;

import java.util.concurrent.atomic.AtomicReference;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.pool.TypePool;

/** Manages {@link TypePool} use across muzzle and instrumentations. */
public class DDSharedTypePools {
  private static final AtomicReference<Supplier> SUPPLIER = new AtomicReference<>();

  public static TypePool typePool(ClassLoader classLoader) {
    return typePool(DDClassFileLocators.classFileLocator(classLoader), classLoader);
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
}

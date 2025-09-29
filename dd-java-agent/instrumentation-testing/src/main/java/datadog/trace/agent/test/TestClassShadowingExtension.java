package datadog.trace.agent.test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.bytebuddy.dynamic.ClassFileLocator;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * This extension does the following:
 *
 * <ul>
 *   <li>ensures that the test class does not refer to bootstrap classes (checks field types, method
 *       return types, argument types)
 *   <li>replaces context classloader with a custom one that "shadows" the test class by loading a
 *       fresh copy of it before every test
 * </ul>
 *
 * @see BootstrapClasspathSetupListener#isBootstrapClass(Class)
 */
public final class TestClassShadowingExtension
    implements BeforeAllCallback, AfterAllCallback, BeforeEachCallback, AfterEachCallback {

  private static final ExtensionContext.Namespace NAMESPACE =
      ExtensionContext.Namespace.create("dd", "spock");

  private static final String INSTRUMENTATION_CLASSLOADER = "instrumentation-class-loader";
  private static final String ORIGINAL_CLASSLOADER = "original-thread-context-class-loader";

  @Override
  public void beforeAll(ExtensionContext ctx) {
    Class<?> testClass = ctx.getRequiredTestClass();
    assertNoBootstrapClassesInTestClass(testClass);

    InstrumentationClassLoader custom =
        new InstrumentationClassLoader(testClass.getClassLoader(), testClass.getName());
    ctx.getStore(NAMESPACE).put(INSTRUMENTATION_CLASSLOADER, custom);
  }

  private static void assertNoBootstrapClassesInTestClass(final Class<?> testClass) {
    for (final Field field : testClass.getDeclaredFields()) {
      assertNotBootstrapClass(testClass, field.getType());
    }
    for (final Method method : testClass.getDeclaredMethods()) {
      assertNotBootstrapClass(testClass, method.getReturnType());
      for (final Class<?> paramType : method.getParameterTypes()) {
        assertNotBootstrapClass(testClass, paramType);
      }
    }
  }

  private static void assertNotBootstrapClass(final Class<?> testClass, final Class<?> clazz) {
    if (BootstrapClasspathSetupListener.isBootstrapClass(clazz)) {
      throw new IllegalStateException(
          testClass.getName()
              + ": Bootstrap classes are not allowed in test class field or method signatures. Offending class: "
              + clazz.getName());
    }
  }

  @Override
  public void afterAll(ExtensionContext ctx) {
    // nothing to clean‑up – garbage collector will take care of loader
  }

  @Override
  public void beforeEach(ExtensionContext ctx) {
    ClassLoader instrumentationClassloader =
        ctx.getStore(NAMESPACE).get(INSTRUMENTATION_CLASSLOADER, ClassLoader.class);
    ctx.getStore(NAMESPACE)
        .put(ORIGINAL_CLASSLOADER, Thread.currentThread().getContextClassLoader());
    Thread.currentThread().setContextClassLoader(instrumentationClassloader);
  }

  @Override
  public void afterEach(ExtensionContext ctx) {
    ClassLoader prev = ctx.getStore(NAMESPACE).remove(ORIGINAL_CLASSLOADER, ClassLoader.class);
    if (prev != null) {
      Thread.currentThread().setContextClassLoader(prev);
    }
  }

  /** Run test classes in a classloader which loads test classes before delegating. */
  private static class InstrumentationClassLoader extends ClassLoader {
    private final ClassLoader parent;
    private final String shadowPrefix;

    InstrumentationClassLoader(ClassLoader parent, String shadowPrefix) {
      super(parent);
      this.parent = parent;
      this.shadowPrefix = shadowPrefix;
    }

    /** Forcefully inject the bytes of clazz into this classloader. */
    Class<?> shadow(Class<?> clazz) throws IOException {
      Class<?> loaded = findLoadedClass(clazz.getName());
      if (loaded != null && loaded.getClassLoader() == this) {
        return loaded;
      }
      try (ClassFileLocator classFileLocator =
          ClassFileLocator.ForClassLoader.of(clazz.getClassLoader())) {
        byte[] classBytes = classFileLocator.locate(clazz.getName()).resolve();
        return defineClass(clazz.getName(), classBytes, 0, classBytes.length);
      }
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
      synchronized (getClassLoadingLock(name)) {
        Class<?> c = findLoadedClass(name);
        if (c != null) {
          return c;
        }
        if (name.startsWith(shadowPrefix)) {
          try {
            Class<?> shadowed = shadow(parent.loadClass(name));
            if (resolve) {
              resolveClass(shadowed);
            }
            return shadowed;
          } catch (Exception ignored) {
            // fall‑back to parent below
          }
        }
        return super.loadClass(name, resolve);
      }
    }
  }
}

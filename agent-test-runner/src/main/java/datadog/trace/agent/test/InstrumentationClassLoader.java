package datadog.trace.agent.test;

import java.io.IOException;
import net.bytebuddy.dynamic.ClassFileLocator;

/** Run test classes in a classloader which loads test classes before delegating. */
public class InstrumentationClassLoader extends java.lang.ClassLoader {
  final ClassLoader parent;
  final String shadowPrefix;

  public InstrumentationClassLoader(final ClassLoader parent, final String shadowPrefix) {
    super(parent);
    this.parent = parent;
    this.shadowPrefix = shadowPrefix;
  }

  /** Forcefully inject the bytes of clazz into this classloader. */
  public Class<?> shadow(final Class<?> clazz) throws IOException {
    final Class<?> loaded = findLoadedClass(clazz.getName());
    if (loaded != null && loaded.getClassLoader() == this) {
      return loaded;
    }
    final ClassFileLocator locator = ClassFileLocator.ForClassLoader.of(clazz.getClassLoader());
    final byte[] classBytes = locator.locate(clazz.getName()).resolve();

    return defineClass(clazz.getName(), classBytes, 0, classBytes.length);
  }

  @Override
  protected Class<?> loadClass(final String name, final boolean resolve)
      throws ClassNotFoundException {
    synchronized (super.getClassLoadingLock(name)) {
      final Class c = findLoadedClass(name);
      if (c != null) {
        return c;
      }
      if (name.startsWith(shadowPrefix)) {
        try {
          return shadow(super.loadClass(name, resolve));
        } catch (final Exception e) {
        }
      }

      return parent.loadClass(name);
    }
  }
}

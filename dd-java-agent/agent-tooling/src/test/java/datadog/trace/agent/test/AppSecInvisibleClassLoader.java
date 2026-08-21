package datadog.trace.agent.test;

import datadog.appsec.api.blocking.BlockingException;
import java.net.URL;
import java.net.URLClassLoader;

/**
 * A {@link URLClassLoader} that delegates to the given parent for everything - including {@code
 * datadog.trace.bootstrap.*} and slf4j, which in production are visible from any classloader -
 * except {@link BlockingException} (which in production is only visible if the appsec module is
 * present) and the given target class name, which this loader defines locally so it gets
 * instrumented as if loaded by an isolated (e.g. plugin/OSGi-style) classloader.
 */
final class AppSecInvisibleClassLoader extends URLClassLoader {
  private final String isolatedClassName;

  AppSecInvisibleClassLoader(URL[] classpath, ClassLoader parent, String isolatedClassName) {
    super(classpath, parent);
    this.isolatedClassName = isolatedClassName;
  }

  @Override
  protected synchronized Class<?> loadClass(String name, boolean resolve)
      throws ClassNotFoundException {
    Class<?> found = findLoadedClass(name);
    if (found == null) {
      if (name.equals(BlockingException.class.getName())) {
        throw new ClassNotFoundException(name);
      }
      found = name.equals(isolatedClassName) ? findClass(name) : super.loadClass(name, resolve);
    }
    if (resolve) {
      resolveClass(found);
    }
    return found;
  }
}

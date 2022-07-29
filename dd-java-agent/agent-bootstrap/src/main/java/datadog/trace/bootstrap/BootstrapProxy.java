package datadog.trace.bootstrap;

import java.net.URL;
import java.net.URLClassLoader;

/**
 * Calling java.lang.instrument.Instrumentation#appendToBootstrapClassLoaderSearch adds a jar to the
 * bootstrap path for class lookup, but not resource lookup. As a workaround we track the jars here,
 * so we can check them for resources before delegating to the real boostrap loader.
 */
public final class BootstrapProxy extends URLClassLoader {
  static {
    ClassLoader.registerAsParallelCapable();
  }

  public BootstrapProxy(final URL url) {
    super(new URL[] {url}, null);
  }

  public BootstrapProxy() {
    super(new URL[0], null);
  }

  @Override
  public void addURL(final URL url) {
    super.addURL(url);
  }

  @Override
  protected Class<?> findClass(final String name) throws ClassNotFoundException {
    throw new ClassNotFoundException(name);
  }
}

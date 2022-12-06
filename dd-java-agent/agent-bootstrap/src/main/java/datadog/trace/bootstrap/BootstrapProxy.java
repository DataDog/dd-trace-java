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

  public static final BootstrapProxy INSTANCE = new BootstrapProxy();

  private BootstrapProxy() {
    super(new URL[0], null);
  }

  public static void addBootstrapResource(final URL url) {
    INSTANCE.addURL(url);
  }

  @Override
  protected Class<?> findClass(final String name) throws ClassNotFoundException {
    throw new ClassNotFoundException(name);
  }
}

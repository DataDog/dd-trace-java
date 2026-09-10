package datadog.trace.agent.test;

import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.bootstrap.blocking.BlockingExceptionHandler;
import java.net.URL;
import java.net.URLClassLoader;

/**
 * A parent-less {@link URLClassLoader} for the blocking exception-handler tests. It returns the
 * {@link BlockingExceptionHandler} and {@link BlockingException} instances already loaded by the
 * current classloader, and loads everything else from the supplied classpath.
 */
final class BlockingTestClassLoader extends URLClassLoader {

  BlockingTestClassLoader(URL[] classpath) {
    super(classpath, null, null);
  }

  @Override
  public Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
    if (name.equals(BlockingExceptionHandler.class.getName())) {
      return BlockingExceptionHandler.class;
    }
    if (name.equals(BlockingException.class.getName())) {
      return BlockingException.class;
    }
    return super.loadClass(name, resolve);
  }
}

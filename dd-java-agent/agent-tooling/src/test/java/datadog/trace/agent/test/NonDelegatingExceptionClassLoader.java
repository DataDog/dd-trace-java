package datadog.trace.agent.test;

import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.bootstrap.blocking.BlockingExceptionHandler;
import java.net.URL;
import java.net.URLClassLoader;

/**
 * A {@link URLClassLoader} that resolves the blocking helper classes directly and delegates
 * everything else to the supplied classpath only (no parent).
 *
 * <p>Kept in Java rather than the Groovy test on purpose: Groovy synthesizes a {@code
 * super$N$loadClass(java.lang.Module, String)} accessor for {@code ClassLoader} subclasses, which
 * references {@code java.lang.Module} and would break this Java 8 test suite.
 */
final class NonDelegatingExceptionClassLoader extends URLClassLoader {

  NonDelegatingExceptionClassLoader(URL[] classpath) {
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

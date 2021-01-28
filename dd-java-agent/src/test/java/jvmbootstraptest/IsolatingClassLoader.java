package jvmbootstraptest;

import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;

/**
 * Isolated {@link ClassLoader} that loads all non-JDK classes from class files instead of
 * delegating.
 */
public class IsolatingClassLoader extends URLClassLoader {
  public IsolatingClassLoader() {
    super(discoverTestClassPath());
  }

  @Override
  public Class<?> loadClass(final String className) throws ClassNotFoundException {
    if (className.startsWith("java.")) {
      return super.loadClass(className);
    }
    return findClass(className);
  }

  private static URL[] discoverTestClassPath() {
    final String resourceName = IsolatingClassLoader.class.getName().replace(".", "/") + ".class";
    final URL resource = Thread.currentThread().getContextClassLoader().getResource(resourceName);
    final String testClassesURI = resource.toString().replace(resourceName, "");
    System.out.println("Loading isolated classes from: " + testClassesURI);
    try {
      return new URL[] {new URI(testClassesURI).toURL()};
    } catch (final Exception e) {
      throw new IllegalStateException(e);
    }
  }
}

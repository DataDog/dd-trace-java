package foo.bar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TestClassLoaderSuite {

  private static final Logger LOGGER = LoggerFactory.getLogger(TestClassLoaderSuite.class);

  public static Class<?> loadClass(final String className) throws ClassNotFoundException {
    LOGGER.debug("Before loadClass");
    final Class<?> result = ClassLoader.getSystemClassLoader().loadClass(className);
    LOGGER.debug("After loadClass {}", result);
    return result;
  }
}

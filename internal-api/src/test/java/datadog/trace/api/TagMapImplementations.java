package datadog.trace.api;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.reflect.Method;

/** Whether a TagMap implementation class has been loaded, without loading it. */
final class TagMapImplementations {
  private TagMapImplementations() {}

  /**
   * True if {@code className} has been loaded by TagMap's class loader. Skips the calling test when
   * the JVM denies the reflective access this needs (JDK 17+ without {@code --add-opens}).
   */
  static boolean isLoaded(String className) {
    Method findLoadedClass;
    try {
      findLoadedClass = ClassLoader.class.getDeclaredMethod("findLoadedClass", String.class);
      findLoadedClass.setAccessible(true);
    } catch (RuntimeException | NoSuchMethodException e) {
      assumeTrue(false, "cannot inspect loaded classes on this JVM: " + e);
      return false;
    }
    try {
      return findLoadedClass.invoke(TagMap.class.getClassLoader(), className) != null;
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }
}

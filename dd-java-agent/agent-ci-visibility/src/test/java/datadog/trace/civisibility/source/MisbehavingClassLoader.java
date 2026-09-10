package datadog.trace.civisibility.source;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * A {@link ClassLoader} that defines classes from an in-memory map and fails resource lookups, used
 * to exercise {@code ByteCodeLinesResolver} when a class cannot be loaded.
 *
 * <p>Kept in Java rather than the Groovy test on purpose: Groovy synthesizes super-accessors for
 * every {@code ClassLoader} overload, including {@code loadClass(java.lang.Module, String)}, which
 * references {@code java.lang.Module} and would break this Java 8 test suite.
 */
final class MisbehavingClassLoader extends ClassLoader {

  private final Map<String, byte[]> classes = new HashMap<>();

  @Override
  public InputStream getResourceAsStream(String name) {
    throw new RuntimeException("Something went wrong");
  }

  @Override
  public Class<?> loadClass(String name) throws ClassNotFoundException {
    byte[] bytes = classes.get(name);
    if (bytes != null) {
      return defineClass(name, bytes, 0, bytes.length);
    }
    return super.loadClass(name);
  }

  void putClass(String name, byte[] bytes) {
    classes.put(name, bytes);
  }
}

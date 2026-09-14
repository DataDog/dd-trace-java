package datadog.trace.civisibility.source;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * A {@link ClassLoader} that defines classes from an in-memory map and returns {@code null} from
 * resource lookups (rather than throwing), used to exercise {@code ByteCodeLinesResolver} when a
 * class's bytecode resource cannot be located.
 *
 * <p>Kept in Java rather than the Java 8 test suite's Groovy counterpart on purpose: see {@link
 * MisbehavingClassLoader} for why.
 */
final class NullResourceClassLoader extends ClassLoader {

  private final Map<String, byte[]> classes = new HashMap<>();

  @Override
  public InputStream getResourceAsStream(String name) {
    return null;
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

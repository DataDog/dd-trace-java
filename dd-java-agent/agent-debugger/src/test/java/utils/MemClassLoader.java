package utils;

import java.util.Map;

// Custom ClassLoader to be able to load nested classes
class MemClassLoader extends ClassLoader {
  private final Map<String, byte[]> classFileBuffers;

  public MemClassLoader(ClassLoader parent, Map<String, byte[]> classFileBuffers) {
    super(parent);
    this.classFileBuffers = classFileBuffers;
  }

  @Override
  protected Class<?> findClass(String name) throws ClassNotFoundException {
    byte[] buffer = classFileBuffers.get(name);
    if (buffer == null) {
      throw new ClassNotFoundException(name);
    }
    return defineClass(name, buffer, 0, buffer.length);
  }
}

package datadog.trace.civisibility.source;

import java.io.InputStream;

public abstract class Utils {

  public static InputStream getClassStream(Class<?> clazz) {
    String className = clazz.getName();
    String classFileName = className.replace('.', '/') + ".class";

    ClassLoader classLoader = clazz.getClassLoader();
    if (classLoader != null) {
      return classLoader.getResourceAsStream(classFileName);
    } else {
      return ClassLoader.getSystemResourceAsStream(classFileName);
    }
  }
}

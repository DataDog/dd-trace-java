package datadog.trace.civisibility.source;

import java.io.IOException;
import java.io.InputStream;

public abstract class Utils {

  public static InputStream getClassStream(Class<?> clazz) throws IOException {
    String className = clazz.getName();
    String classPath = "/" + className.replace('.', '/') + ".class";
    return clazz.getResourceAsStream(classPath);
  }
}

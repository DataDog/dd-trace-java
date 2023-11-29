package datadog.trace.civisibility.source;

import java.io.IOException;
import java.io.InputStream;

public abstract class Utils {

  public static InputStream getClassStream(Class<?> clazz) throws IOException {
    String className = clazz.getName();
    InputStream classStream = clazz.getResourceAsStream(toResourceName(className));
    if (classStream != null) {
      return classStream;
    } else {
      // might be auto-generated inner class (e.g. Mockito mock)
      String topLevelClassName = stripNestedClassNames(clazz.getName());
      return clazz.getResourceAsStream(toResourceName(topLevelClassName));
    }
  }

  private static String toResourceName(String className) {
    return "/" + className.replace('.', '/') + ".class";
  }

  public static String stripNestedClassNames(String className) {
    int innerClassNameIdx = className.indexOf('$');
    if (innerClassNameIdx >= 0) {
      return className.substring(0, innerClassNameIdx);
    } else {
      return className;
    }
  }
}

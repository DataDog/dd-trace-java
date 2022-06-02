package com.datadog.debugger.agent;

/** Helper class for matching class name */
public class TypeNameHelper {

  public static String extractSimpleName(Class<?> clazz) {
    String typeName;
    try {
      typeName = clazz.getSimpleName();
    } catch (Throwable ex) {
      // if class is from DataDog Classloader can have issues getting simple name
      typeName = "";
    }
    if (typeName.equals("")) {
      // fallback to parsing getName()
      typeName = extractSimpleNameFromName(clazz.getName());
    }
    return typeName;
  }

  static String extractSimpleNameFromName(String className) {
    int idx = className.lastIndexOf('$');
    String typeName;
    if (idx != -1) {
      typeName = className.substring(idx + 1); // strip the package & enclosing name
      if (typeName.length() > 0 && Character.isDigit(typeName.charAt(0))) {
        // this is anonymous class let's keep the enclosing name
        typeName = className.substring(className.lastIndexOf('.') + 1); // strip the package name
      }
    } else {
      typeName = className.substring(className.lastIndexOf('.') + 1); // strip the package name
    }
    return typeName;
  }
}

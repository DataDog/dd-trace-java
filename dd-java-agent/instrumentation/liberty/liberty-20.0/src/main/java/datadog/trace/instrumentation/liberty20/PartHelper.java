package datadog.trace.instrumentation.liberty20;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public class PartHelper {

  public static List<String> extractFilenames(Collection<?> parts) {
    if (parts == null || parts.isEmpty()) {
      return Collections.emptyList();
    }
    Class<?> partClass = parts.iterator().next().getClass();
    Method getSubmittedFileName = resolveMethod(partClass, "getSubmittedFileName");
    Method getHeader = resolveMethod(partClass, "getHeader", String.class);

    List<String> filenames = new ArrayList<>();
    for (Object part : parts) {
      String name = getSubmittedFilename(getSubmittedFileName, part);
      if (name == null) {
        name = getFilenameFromContentDisposition(getHeader, part);
      }
      if (name != null && !name.isEmpty()) {
        filenames.add(name);
      }
    }
    return filenames;
  }

  private static Method resolveMethod(Class<?> clazz, String name, Class<?>... params) {
    try {
      return clazz.getMethod(name, params);
    } catch (Exception ignored) {
      return null;
    }
  }

  private static String getSubmittedFilename(Method method, Object part) {
    if (method == null) {
      return null;
    }
    try {
      return (String) method.invoke(part);
    } catch (Exception ignored) {
      return null;
    }
  }

  private static String getFilenameFromContentDisposition(Method getHeader, Object part) {
    if (getHeader == null) {
      return null;
    }
    try {
      String cd = (String) getHeader.invoke(part, "content-disposition");
      if (cd == null) {
        return null;
      }
      for (String tok : cd.split(";")) {
        tok = tok.trim();
        if (tok.startsWith("filename=")) {
          String name = tok.substring(9).trim();
          if (name.startsWith("\"") && name.endsWith("\"")) {
            name = name.substring(1, name.length() - 1);
          }
          return name;
        }
      }
    } catch (Exception ignored) {
    }
    return null;
  }
}

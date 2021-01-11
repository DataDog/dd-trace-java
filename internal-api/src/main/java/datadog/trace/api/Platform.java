package datadog.trace.api;

import datadog.trace.unsafe.CASFactory;
import datadog.trace.unsafe.ConcurrentArrayOperations;
import datadog.trace.util.Strings;
import datadog.trace.unsafe.IntCAS;
import datadog.trace.unsafe.LongCAS;
import datadog.trace.unsafe.ReferenceCAS;
import java.lang.reflect.InvocationTargetException;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class Platform {

  private static final int JAVA_MAJOR_VERSION = getJavaMajorVersion();

  private static int getJavaMajorVersion() {
    return parseJavaVersion(System.getProperty("java.version"));
  }

  static int parseJavaVersion(String javaVersion) {
    javaVersion = Strings.replace(javaVersion, "-ea", "");
    try {
      if (javaVersion.startsWith("1.")) {
        int secondDot = javaVersion.indexOf('.', 2);
        return Integer.parseInt(
            javaVersion.substring(2, secondDot < 0 ? javaVersion.length() : secondDot));
      } else {
        int firstDot = javaVersion.indexOf('.');
        if (firstDot > 0) {
          return Integer.parseInt(javaVersion.substring(0, firstDot));
        }
        return Integer.parseInt(javaVersion);
      }
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  public static boolean isJavaVersionAtLeast(int version) {
    return JAVA_MAJOR_VERSION >= version;
  }

  public static ConcurrentArrayOperations concurrentArrayOperations() {
    return ConcurrentOperationsHolder.OPERATIONS;
  }

  public static <T> ReferenceCAS<T> createReferenceCAS(
      Class<?> type, String field, Class<T> fieldType) {
    return ConcurrentOperationsHolder.CAS_FACTORY.createReferenceCAS(type, field, fieldType);
  }

  public static LongCAS createLongCAS(Class<?> type, String field) {
    return ConcurrentOperationsHolder.CAS_FACTORY.createLongCAS(type, field);
  }

  public static IntCAS createIntCAS(Class<?> type, String field) {
    return ConcurrentOperationsHolder.CAS_FACTORY.createIntCAS(type, field);
  }

  private static final class ConcurrentOperationsHolder {
    static final ConcurrentArrayOperations OPERATIONS;
    static final CASFactory CAS_FACTORY;

    static {
      ConcurrentArrayOperations operations;
      CASFactory casFactory;
      try {
        if (isJavaVersionAtLeast(9)) {
          // we need to avoid loading sun.misc.Unsafe after Java 9, because it logs
          // the illegal access, which alarms users, and at some point in the future,
          // it will become impossible to load.
          operations =
              loadAndInstantiate(
                  "datadog.trace.core.varhandles.VarHandleConcurrentArrayOperations");
          casFactory = loadAndInstantiate("datadog.trace.core.varhandles.VarHandleCASFactory");
        } else {
          operations =
              loadAndInstantiate("datadog.trace.core.unsafe.UnsafeConcurrentArrayOperations");
          casFactory = loadAndInstantiate("datadog.trace.core.unsafe.UnsafeCASFactory");
        }
      } catch (Throwable t) {
        log.debug(
            "Unexpectedly could not load ConcurrentArrayOperations or CASFactory",
            t);
        operations = null;
        casFactory = null;
      }
      OPERATIONS = operations;
      CAS_FACTORY = casFactory;
    }

    @SuppressWarnings("unchecked")
    private static <T> T loadAndInstantiate(String className)
        throws ClassNotFoundException, NoSuchMethodException, IllegalAccessException,
            InvocationTargetException, InstantiationException {
      return (T)
          ConcurrentOperationsHolder.class
              .getClassLoader()
              .loadClass(className)
              .getDeclaredConstructor()
              .newInstance();
    }
  }
}

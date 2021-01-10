package datadog.trace.api;

import datadog.trace.unsafe.ConcurrentArrayOperations;
import datadog.trace.util.Strings;
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
    return ConcurrentArrayOperationsHolder.OPERATIONS;
  }

  private static final class NonConcurrentArrayOperationsFallback
      implements ConcurrentArrayOperations {

    @Override
    public Object getObjectVolatile(Object[] array, int index) {
      return array[index];
    }

    @Override
    public void putObjectVolatile(Object[] array, int index, Object x) {
      array[index] = x;
    }

    @Override
    public int getIntVolatile(int[] array, int index) {
      return array[index];
    }

    @Override
    public long getLongVolatile(long[] array, int index) {
      return array[index];
    }

    @Override
    public void putIntVolatile(int[] array, int index, int x) {
      array[index] = x;
    }

    @Override
    public boolean getBooleanVolatile(boolean[] array, int index) {
      return array[index];
    }

    @Override
    public void putBooleanVolatile(boolean[] array, int index, boolean x) {
      array[index] = x;
    }

    @Override
    public void putLongVolatile(long[] array, int index, long x) {
      array[index] = x;
    }

    @Override
    public void putOrderedObject(Object[] array, int index, Object x) {
      array[index] = x;
    }

    @Override
    public void putOrderedInt(int[] array, int index, int x) {
      array[index] = x;
    }

    @Override
    public void putOrderedLong(long[] array, int index, long x) {
      array[index] = x;
    }
  }

  private static final class ConcurrentArrayOperationsHolder {
    static final ConcurrentArrayOperations OPERATIONS;

    static {
      ConcurrentArrayOperations operations;
      try {
        if (isJavaVersionAtLeast(9)) {
          // we need to avoid loading sun.misc.Unsafe after Java 9, because it logs
          // the illegal access, which alarms users, and at some point in the future,
          // it will become impossible to load.
          operations =
              loadAndInstantiate(
                  "datadog.trace.core.varhandles.VarHandleConcurrentArrayOperations");
        } else {
          operations =
              loadAndInstantiate("datadog.trace.core.unsafe.UnsafeConcurrentArrayOperations");
        }
      } catch (Throwable t) {
        log.debug(
            "Unexpectedly could not load ConcurrentArrayOperations, falling back to default implementation without visibility guarantees",
            t);
        // Weird visibility bugs will likely follow from this,
        // but the tracer will more or less function as expected
        operations = new NonConcurrentArrayOperationsFallback();
      }
      OPERATIONS = operations;
    }

    private static ConcurrentArrayOperations loadAndInstantiate(String className)
        throws ClassNotFoundException, NoSuchMethodException, IllegalAccessException,
            InvocationTargetException, InstantiationException {
      return (ConcurrentArrayOperations)
          ConcurrentArrayOperationsHolder.class
              .getClassLoader()
              .loadClass(className)
              .getDeclaredConstructor()
              .newInstance();
    }
  }
}

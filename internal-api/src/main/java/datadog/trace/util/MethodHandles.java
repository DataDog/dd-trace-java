package datadog.trace.util;

import datadog.trace.api.telemetry.LogCollector;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Arrays;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MethodHandles {

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.class);

  private final java.lang.invoke.MethodHandles.Lookup lookup =
      java.lang.invoke.MethodHandles.lookup();
  private final ClassLoader classLoader;

  public MethodHandles(ClassLoader classLoader) {
    this.classLoader = classLoader;
  }

  public MethodHandle privateFieldGetter(String className, String fieldName) {
    Class<?> clazz = loadClass(className);
    return clazz != null ? privateFieldGetter(clazz, fieldName) : null;
  }

  public MethodHandle privateFieldGetter(Class<?> clazz, String fieldName) {
    return AccessController.doPrivileged(
        (PrivilegedAction<MethodHandle>)
            () -> {
              try {
                try {
                  SecurityManager sm = System.getSecurityManager();
                  if (sm != null) {
                    String packageName = clazz.getPackage().getName();
                    sm.checkPackageAccess(packageName);
                  }
                } catch (UnsupportedOperationException e) {
                  // ignore
                }

                Field field = clazz.getDeclaredField(fieldName);
                field.setAccessible(true);
                return lookup.unreflectGetter(field);

              } catch (Throwable t) {
                log.debug(
                    LogCollector.EXCLUDE_TELEMETRY,
                    "Could not get private field {} getter from class {}",
                    fieldName,
                    clazz.getName(),
                    t);
                return null;
              }
            });
  }

  public MethodHandle privateFieldSetter(String className, String fieldName) {
    Class<?> clazz = loadClass(className);
    return clazz != null ? privateFieldSetter(clazz, fieldName) : null;
  }

  @SuppressForbidden
  public MethodHandle privateFieldSetter(Class<?> clazz, String fieldName) {
    return AccessController.doPrivileged(
        (PrivilegedAction<MethodHandle>)
            () -> {
              try {
                try {
                  SecurityManager sm = System.getSecurityManager();
                  if (sm != null) {
                    String packageName = clazz.getPackage().getName();
                    sm.checkPackageAccess(packageName);
                  }
                } catch (UnsupportedOperationException e) {
                  // ignore
                }

                Field field = clazz.getDeclaredField(fieldName);
                field.setAccessible(true);
                return lookup.unreflectSetter(field);

              } catch (Throwable t) {
                log.debug(
                    LogCollector.EXCLUDE_TELEMETRY,
                    "Could not get private field {} setter from class {}",
                    fieldName,
                    clazz.getName(),
                    t);
                return null;
              }
            });
  }

  public MethodHandle constructor(String className, Class<?>... parameterTypes) {
    Class<?> clazz = loadClass(className);
    return clazz != null ? constructor(clazz, parameterTypes) : null;
  }

  public MethodHandle constructor(Class<?> clazz, Class<?>... parameterTypes) {
    return AccessController.doPrivileged(
        (PrivilegedAction<MethodHandle>)
            () -> {
              try {
                try {
                  SecurityManager sm = System.getSecurityManager();
                  if (sm != null) {
                    String packageName = clazz.getPackage().getName();
                    sm.checkPackageAccess(packageName);
                  }
                } catch (UnsupportedOperationException e) {
                  // ignore
                }

                Constructor<?> constructor = clazz.getDeclaredConstructor(parameterTypes);
                constructor.setAccessible(true);
                return lookup.unreflectConstructor(constructor);

              } catch (Throwable t) {
                log.debug(
                    LogCollector.EXCLUDE_TELEMETRY,
                    "Could not get constructor accepting {} from class {}",
                    Arrays.toString(parameterTypes),
                    clazz.getName(),
                    t);
                return null;
              }
            });
  }

  public MethodHandle method(String className, String methodName, Class<?>... parameterTypes) {
    Class<?> clazz = loadClass(className);
    return clazz != null ? method(clazz, methodName, parameterTypes) : null;
  }

  public MethodHandle method(Class<?> clazz, String methodName, Class<?>... parameterTypes) {
    return AccessController.doPrivileged(
        (PrivilegedAction<MethodHandle>)
            () -> {
              try {
                try {
                  SecurityManager sm = System.getSecurityManager();
                  if (sm != null) {
                    String packageName = clazz.getPackage().getName();
                    sm.checkPackageAccess(packageName);
                  }
                } catch (UnsupportedOperationException e) {
                  // ignore
                }

                Method method = clazz.getDeclaredMethod(methodName, parameterTypes);
                method.setAccessible(true);
                return lookup.unreflect(method);

              } catch (Throwable t) {
                log.debug(
                    LogCollector.EXCLUDE_TELEMETRY,
                    "Could not get method {} accepting {} from class {}",
                    methodName,
                    Arrays.toString(parameterTypes),
                    clazz,
                    t);
                return null;
              }
            });
  }

  public MethodHandle method(Class<?> clazz, Predicate<Method> filter) {
    return AccessController.doPrivileged(
        (PrivilegedAction<MethodHandle>)
            () -> {
              try {
                try {
                  SecurityManager sm = System.getSecurityManager();
                  if (sm != null) {
                    String packageName = clazz.getPackage().getName();
                    sm.checkPackageAccess(packageName);
                  }
                } catch (UnsupportedOperationException e) {
                  // ignore
                }

                Method[] methods = clazz.getDeclaredMethods();
                for (Method method : methods) {
                  if (filter.test(method)) {
                    method.setAccessible(true);
                    return lookup.unreflect(method);
                  }
                }

                log.debug("Could not find desired method in class {}", clazz);
                return null;

              } catch (Throwable t) {
                log.debug(
                    LogCollector.EXCLUDE_TELEMETRY,
                    "Could not find desired method in class {}",
                    clazz,
                    t);
                return null;
              }
            });
  }

  private Class<?> loadClass(String className) {
    try {
      return classLoader.loadClass(className);
    } catch (Throwable t) {
      log.debug(LogCollector.EXCLUDE_TELEMETRY, "Could not load class {}", className, t);
      return null;
    }
  }

  @SuppressWarnings("unchecked")
  public <T> T invoke(MethodHandle handle, Object... arguments) {
    if (handle == null) {
      return null;
    }
    try {
      return (T) handle.invokeWithArguments(arguments);
    } catch (Throwable t) {
      log.error(
          "Error while invoking method handle {} with arguments {}",
          handle,
          Arrays.toString(arguments),
          t);
      return null;
    }
  }
}

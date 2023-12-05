package datadog.trace.util;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
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
    try {
      Class<?> clazz = classLoader.loadClass(className);
      return privateFieldGetter(clazz, fieldName);

    } catch (Throwable t) {
      log.debug("Could not get private field {} getter from class {}", fieldName, className, t);
      return null;
    }
  }

  @SuppressFBWarnings("REFLF_REFLECTION_MAY_INCREASE_ACCESSIBILITY_OF_FIELD")
  public MethodHandle privateFieldGetter(Class<?> clazz, String fieldName) {
    try {
      Field field = clazz.getDeclaredField(fieldName);
      field.setAccessible(true);
      return lookup.unreflectGetter(field);

    } catch (Throwable t) {
      log.debug("Could not get private field {} getter from class {}", fieldName, clazz, t);
      return null;
    }
  }

  public MethodHandle constructor(String className, Class<?>... parameterTypes) {
    try {
      Class<?> clazz = classLoader.loadClass(className);
      Constructor<?> constructor = clazz.getDeclaredConstructor(parameterTypes);
      constructor.setAccessible(true);
      return lookup.unreflectConstructor(constructor);

    } catch (Throwable t) {
      log.debug(
          "Could not get constructor accepting {} from class {}",
          Arrays.toString(parameterTypes),
          className,
          t);
      return null;
    }
  }

  public MethodHandle method(Class<?> clazz, String methodName, Class<?>... parameterTypes) {
    try {
      Method method = clazz.getDeclaredMethod(methodName, parameterTypes);
      method.setAccessible(true);
      return lookup.unreflect(method);

    } catch (Throwable t) {
      log.debug(
          "Could not get method {} accepting {} from class {}",
          methodName,
          Arrays.toString(parameterTypes),
          clazz,
          t);
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

package datadog.trace.util;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
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

  @SuppressFBWarnings("REFLF_REFLECTION_MAY_INCREASE_ACCESSIBILITY_OF_FIELD")
  public MethodHandle privateFieldGetter(String className, String fieldName) {
    try {
      Class<?> clazz = classLoader.loadClass(className);
      Field field = clazz.getDeclaredField(fieldName);
      field.setAccessible(true);
      return lookup.unreflectGetter(field);

    } catch (Throwable t) {
      log.error("Could not get private field {} getter from class {}", fieldName, className, t);
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
      log.error(
          "Could not get constructor accepting {} from class {}",
          Arrays.toString(parameterTypes),
          className,
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

package datadog.trace.test.util;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Evaluates static condition methods referenced by {@link Flaky}. */
public final class FlakyConditionMethod {
  private FlakyConditionMethod() {}

  /** Evaluates a condition in {@code fully.qualified.Class#method} format. */
  public static boolean evaluate(String reference, ClassLoader classLoader) {
    int separator = reference.lastIndexOf('#');
    if (separator <= 0 || separator == reference.length() - 1) {
      throw new IllegalArgumentException(
          "@Flaky condition method must use the format fully.qualified.Class#method");
    }

    String className = reference.substring(0, separator);
    String methodName = reference.substring(separator + 1);
    try {
      Class<?> conditionClass = classLoader.loadClass(className);
      Method method = conditionClass.getDeclaredMethod(methodName);
      if (!Modifier.isStatic(method.getModifiers())) {
        throw new IllegalArgumentException(
            "@Flaky condition method " + reference + " must be static");
      }
      if (method.getReturnType() != boolean.class && method.getReturnType() != Boolean.class) {
        throw new IllegalArgumentException(
            "@Flaky condition method " + reference + " must return boolean");
      }
      method.setAccessible(true);
      return Boolean.TRUE.equals(method.invoke(null));
    } catch (InvocationTargetException e) {
      throw new IllegalArgumentException(
          "Could not evaluate @Flaky condition method " + reference, e.getCause());
    } catch (ReflectiveOperationException e) {
      throw new IllegalArgumentException(
          "Could not evaluate @Flaky condition method " + reference, e);
    }
  }
}

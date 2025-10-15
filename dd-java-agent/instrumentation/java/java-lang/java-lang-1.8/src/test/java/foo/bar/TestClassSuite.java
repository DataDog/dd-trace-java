package foo.bar;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TestClassSuite {

  private static final Logger LOGGER = LoggerFactory.getLogger(TestClassSuite.class);

  public static Class<?> forName(final String className) throws ClassNotFoundException {
    LOGGER.debug("Before forName");
    final Class<?> result = Class.forName(className);
    LOGGER.debug("After forName {}", result);
    return result;
  }

  public static Class<?> forName(
      final String className, boolean initialize, final ClassLoader loader)
      throws ClassNotFoundException {
    LOGGER.debug("Before forName");
    final Class<?> result = Class.forName(className, initialize, loader);
    LOGGER.debug("After forName {}", result);
    return result;
  }

  public static Method getMethod(
      final Class<?> clazz, final String method, final Class<?>... parameterTypes)
      throws NoSuchMethodException {
    LOGGER.debug("Before getMethod");
    final Method result = clazz.getMethod(method, parameterTypes);
    LOGGER.debug("After getMethod {}", result);
    return result;
  }

  public static Method getDeclaredMethod(
      final Class<?> clazz, final String method, final Class<?>... parameterTypes)
      throws NoSuchMethodException {
    LOGGER.debug("Before getDeclaredMethod");
    final Method result = clazz.getDeclaredMethod(method, parameterTypes);
    LOGGER.debug("After getDeclaredMethod {}", result);
    return result;
  }

  public static Field getField(final Class<?> clazz, final String field)
      throws NoSuchFieldException {
    LOGGER.debug("Before getField");
    final Field result = clazz.getField(field);
    LOGGER.debug("After getField {}", result);
    return result;
  }

  public static Field getDeclaredField(final Class<?> clazz, final String field)
      throws NoSuchFieldException {
    LOGGER.debug("Before getDeclaredField");
    final Field result = clazz.getDeclaredField(field);
    LOGGER.debug("After getDeclaredField {}", result);
    return result;
  }
}

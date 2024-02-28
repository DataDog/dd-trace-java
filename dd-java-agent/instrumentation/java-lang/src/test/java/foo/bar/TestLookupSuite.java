package foo.bar;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TestLookupSuite {

  private static final Logger LOGGER = LoggerFactory.getLogger(TestLookupSuite.class);
  private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

  public static void findSetter(
      final Class<?> clazz, final String method, final Class<?> fieldsType) {
    LOGGER.debug("Before findSetter");
    final MethodHandle result;
    try {
      result = LOOKUP.findSetter(clazz, method, fieldsType);
      LOGGER.debug("After findSetter {}", result);
    } catch (Exception e) {
      LOGGER.debug("Error in findSetter", e);
    }
  }

  public static void findStaticSetter(
      final Class<?> clazz, final String method, final Class<?> fieldsType) {
    LOGGER.debug("Before findStaticSetter");
    final MethodHandle result;
    try {
      result = LOOKUP.findStaticSetter(clazz, method, fieldsType);
      LOGGER.debug("After findStaticSetter {}", result);
    } catch (Exception e) {
      LOGGER.debug("Error in findStaticSetter", e);
    }
  }

  public static void findGetter(
      final Class<?> clazz, final String method, final Class<?> fieldsType) {
    LOGGER.debug("Before findGetter");
    final MethodHandle result;
    try {
      result = LOOKUP.findGetter(clazz, method, fieldsType);
      LOGGER.debug("After findGetter {}", result);
    } catch (Exception e) {
      LOGGER.debug("Error in findGetter", e);
    }
  }

  public static void findStaticGetter(
      final Class<?> clazz, final String method, final Class<?> fieldsType) {
    LOGGER.debug("Before findStaticGetter");
    final MethodHandle result;
    try {
      result = LOOKUP.findStaticGetter(clazz, method, fieldsType);
      LOGGER.debug("After findStaticGetter {}", result);
    } catch (Exception e) {
      LOGGER.debug("Error in findStaticGetter", e);
    }
  }

  public static void bind(final Object obj, final String methodName, final MethodType methodType) {
    LOGGER.debug("Before bind");
    final MethodHandle result;
    try {
      result = LOOKUP.bind(obj, methodName, methodType);
      LOGGER.debug("After bind {}", result);
    } catch (Exception e) {
      LOGGER.debug("Error in bind", e);
    }
  }

  public static void findSpecial(
      final Class<?> clazz, final String method, final MethodType methodType) {
    LOGGER.debug("Before findSpecial");
    final MethodHandle result;
    try {
      result = LOOKUP.findSpecial(clazz, method, methodType, Class.class);
      LOGGER.debug("After findSpecial {}", result);
    } catch (Exception e) {
      LOGGER.debug("Error in findSpecial", e);
    }
  }

  public static void findStatic(
      final Class<?> clazz, final String method, final MethodType methodType) {
    LOGGER.debug("Before findStatic");
    final MethodHandle result;
    try {
      result = LOOKUP.findStatic(clazz, method, methodType);
      LOGGER.debug("After findStatic {}", result);
    } catch (Exception e) {
      LOGGER.debug("Error in findStatic", e);
    }
  }

  public static void findVirtual(
      final Class<?> clazz, final String method, final MethodType methodType) {
    LOGGER.debug("Before findVirtual");
    final MethodHandle result;
    try {
      result = LOOKUP.findVirtual(clazz, method, methodType);
      LOGGER.debug("After findVirtual {}", result);
    } catch (Exception e) {
      LOGGER.debug("Error in findVirtual", e);
    }
  }
}

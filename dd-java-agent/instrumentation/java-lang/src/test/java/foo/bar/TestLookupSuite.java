package foo.bar;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TestLookupSuite {

  private static final Logger LOGGER = LoggerFactory.getLogger(TestLookupSuite.class);
  private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

  public static void bind(final String value) {
    LOGGER.debug("Before bind");
    final MethodHandle result;
    try {
      result = LOOKUP.bind(null, value, MethodType.methodType(Class.class));
      LOGGER.debug("After bind {}", result);
    } catch (Exception e) {
      LOGGER.debug("Error in bind", e);
    }
  }

  public static void findGetter(final String value) {
    LOGGER.debug("Before findGetter");
    final MethodHandle result;
    try {
      result = LOOKUP.findGetter(null, value, Class.class);
      LOGGER.debug("After findGetter {}", result);
    } catch (Exception e) {
      LOGGER.debug("Error in findGetter", e);
    }
  }

  public static void findSetter(final String value) {
    LOGGER.debug("Before findSetter");
    final MethodHandle result;
    try {
      result = LOOKUP.findSetter(null, value, Class.class);
      LOGGER.debug("After findSetter {}", result);
    } catch (Exception e) {
      LOGGER.debug("Error in findSetter", e);
    }
  }

  public static void findStaticGetter(final String value) {
    LOGGER.debug("Before findStaticGetter");
    final MethodHandle result;
    try {
      result = LOOKUP.findStaticGetter(null, value, Class.class);
      LOGGER.debug("After findStaticGetter {}", result);
    } catch (Exception e) {
      LOGGER.debug("Error in findStaticGetter", e);
    }
  }

  public static void findStaticSetter(final String value) {
    LOGGER.debug("Before findStaticSetter");
    final MethodHandle result;
    try {
      result = LOOKUP.findStaticSetter(null, value, Class.class);
      LOGGER.debug("After findStaticSetter {}", result);
    } catch (Exception e) {
      LOGGER.debug("Error in findStaticSetter", e);
    }
  }

  public static void findSpecial(final String value) {
    LOGGER.debug("Before findSpecial");
    final MethodHandle result;
    try {
      result = LOOKUP.findSpecial(null, value, MethodType.methodType(Class.class), Class.class);
      LOGGER.debug("After findSpecial {}", result);
    } catch (Exception e) {
      LOGGER.debug("Error in findSpecial", e);
    }
  }

  public static void findStatic(final String value) {
    LOGGER.debug("Before findStatic");
    final MethodHandle result;
    try {
      result = LOOKUP.findStatic(null, value, MethodType.methodType(Class.class));
      LOGGER.debug("After findStatic {}", result);
    } catch (Exception e) {
      LOGGER.debug("Error in findStatic", e);
    }
  }

  public static void findVirtual(final String value) {
    LOGGER.debug("Before findVirtual");
    final MethodHandle result;
    try {
      result = LOOKUP.findVirtual(null, value, MethodType.methodType(Class.class));
      LOGGER.debug("After findVirtual {}", result);
    } catch (Exception e) {
      LOGGER.debug("Error in findVirtual", e);
    }
  }
}

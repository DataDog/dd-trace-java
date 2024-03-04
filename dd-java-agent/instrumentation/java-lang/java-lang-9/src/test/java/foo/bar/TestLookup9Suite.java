package foo.bar;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TestLookup9Suite {

  private static final Logger LOGGER = LoggerFactory.getLogger(TestLookup9Suite.class);
  private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

  public static void findClass(final String className) {
    LOGGER.debug("Before findClass");
    final Class<?> result;
    try {
      result = LOOKUP.findClass(className);
      LOGGER.debug("After findClass {}", result);
    } catch (Exception e) {
      LOGGER.debug("Error in findClass", e);
    }
  }

  public static void findStaticVarHandle(
      final Class<?> clazz, final String field, final Class<?> fieldType) {
    LOGGER.debug("Before findStaticVarHandle");
    final VarHandle result;
    try {
      result = LOOKUP.findStaticVarHandle(clazz, field, fieldType);
      LOGGER.debug("After findStaticVarHandle {}", result);
    } catch (Exception e) {
      LOGGER.debug("Error in findStaticVarHandle", e);
    }
  }

  public static void findVarHandle(
      final Class<?> clazz, final String field, final Class<?> fieldType) {
    LOGGER.debug("Before findVarHandle");
    final VarHandle result;
    try {
      result = LOOKUP.findVarHandle(clazz, field, fieldType);
      LOGGER.debug("After findVarHandle {}", result);
    } catch (Exception e) {
      LOGGER.debug("Error in findVarHandle", e);
    }
  }
}

package datadog.trace.util;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.misc.Unsafe;

public abstract class UnsafeUtils {

  private static final Logger log = LoggerFactory.getLogger(UnsafeUtils.class);

  private static final Unsafe UNSAFE = getUnsafe();

  private static Unsafe getUnsafe() {
    try {
      Field f = Unsafe.class.getDeclaredField("theUnsafe");
      f.setAccessible(true);
      return (Unsafe) f.get(null);

    } catch (Throwable t) {
      log.debug("Unsafe is unavailable", t);
      return null;
    }
  }

  /**
   * Tries to create a shallow clone of the provided object: another instance of the same class
   * whose fields have the same values as the original (for reference fields that means referring to
   * the same objects).
   *
   * <p>If cloning fails, the original instance is returned.
   *
   * @param original Original object
   * @return A shallow clone
   * @param <T> Type of the object being cloned
   */
  @SuppressWarnings("unchecked")
  public static <T> T tryShallowClone(T original) {
    if (UNSAFE == null) {
      log.debug("Unsafe is unavailable, {} will not be cloned", original);
      return original;
    }
    try {
      Class<?> clazz = original.getClass();
      T clone = (T) UNSAFE.allocateInstance(clazz);

      while (clazz != Object.class) {
        cloneFields(clazz, original, clone);
        clazz = clazz.getSuperclass();
      }
      return clone;

    } catch (Throwable t) {
      log.debug("Error while cloning {}: {}", original, t);
      return original;
    }
  }

  private static void cloneFields(Class<?> clazz, Object original, Object clone) throws Exception {
    for (Field field : clazz.getDeclaredFields()) {
      if ((field.getModifiers() & Modifier.FINAL) != 0) {
        log.debug(
            "Skipping cloning final field {}. Final fields cannot be mutated. See JEP 500 for more details.",
            field.getName());
        continue;
      }
      field.setAccessible(true);
      Object fieldValue = field.get(original);
      field.set(clone, fieldValue);
    }
  }
}

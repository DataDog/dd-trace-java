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

  /**
   * Copies field values using {@link Unsafe} field offsets instead of core reflection or method
   * handles: mutating final fields with those APIs is forbidden by <a
   * href="https://openjdk.org/jeps/500">JEP 500</a>, while Unsafe memory access is not affected by
   * it (and the clone is a fresh, unpublished instance allocated with {@link
   * Unsafe#allocateInstance(Class)}, so the writes are safe).
   */
  private static void cloneFields(Class<?> clazz, Object original, Object clone) {
    for (Field field : clazz.getDeclaredFields()) {
      if ((field.getModifiers() & Modifier.STATIC) != 0) {
        continue;
      }
      long offset = UNSAFE.objectFieldOffset(field);
      Class<?> type = field.getType();
      if (!type.isPrimitive()) {
        UNSAFE.putObject(clone, offset, UNSAFE.getObject(original, offset));
      } else if (type == int.class) {
        UNSAFE.putInt(clone, offset, UNSAFE.getInt(original, offset));
      } else if (type == long.class) {
        UNSAFE.putLong(clone, offset, UNSAFE.getLong(original, offset));
      } else if (type == boolean.class) {
        UNSAFE.putBoolean(clone, offset, UNSAFE.getBoolean(original, offset));
      } else if (type == byte.class) {
        UNSAFE.putByte(clone, offset, UNSAFE.getByte(original, offset));
      } else if (type == char.class) {
        UNSAFE.putChar(clone, offset, UNSAFE.getChar(original, offset));
      } else if (type == short.class) {
        UNSAFE.putShort(clone, offset, UNSAFE.getShort(original, offset));
      } else if (type == float.class) {
        UNSAFE.putFloat(clone, offset, UNSAFE.getFloat(original, offset));
      } else if (type == double.class) {
        UNSAFE.putDouble(clone, offset, UNSAFE.getDouble(original, offset));
      }
    }
  }
}

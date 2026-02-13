package datadog.trace.util;

import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.misc.Unsafe;

public abstract class UnsafeUtils {

  private static final Logger log = LoggerFactory.getLogger(UnsafeUtils.class);

  private static final Unsafe UNSAFE = getUnsafe();

  /** Base offset for byte[] access via Unsafe. */
  public static final long BYTE_ARRAY_BASE_OFFSET;

  static {
    if (UNSAFE == null) {
      throw new ExceptionInInitializerError(
          "sun.misc.Unsafe is not available on this JVM. "
              + "The Datadog tracer requires Unsafe for performance-critical operations.");
    }
    BYTE_ARRAY_BASE_OFFSET = Unsafe.ARRAY_BYTE_BASE_OFFSET;
  }

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

  /** Returns true if Unsafe is available on this JVM. */
  public static boolean isAvailable() {
    return UNSAFE != null;
  }

  // ── Array access primitives for direct byte[] manipulation ──

  public static long getLong(Object base, long offset) {
    return UNSAFE.getLong(base, offset);
  }

  public static int getInt(Object base, long offset) {
    return UNSAFE.getInt(base, offset);
  }

  public static short getShort(Object base, long offset) {
    return UNSAFE.getShort(base, offset);
  }

  public static byte getByte(Object base, long offset) {
    return UNSAFE.getByte(base, offset);
  }

  public static void putLong(Object base, long offset, long value) {
    UNSAFE.putLong(base, offset, value);
  }

  public static void putInt(Object base, long offset, int value) {
    UNSAFE.putInt(base, offset, value);
  }

  public static void putShort(Object base, long offset, short value) {
    UNSAFE.putShort(base, offset, value);
  }

  public static void putByte(Object base, long offset, byte value) {
    UNSAFE.putByte(base, offset, value);
  }

  public static void copyMemory(
      Object srcBase, long srcOffset, Object destBase, long destOffset, long bytes) {
    UNSAFE.copyMemory(srcBase, srcOffset, destBase, destOffset, bytes);
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

  // TODO: JEP 500 - avoid mutating final fields
  @SuppressForbidden
  private static void cloneFields(Class<?> clazz, Object original, Object clone) throws Exception {
    for (Field field : clazz.getDeclaredFields()) {
      if ((field.getModifiers() & Modifier.STATIC) != 0) {
        continue;
      }
      field.setAccessible(true);
      Object fieldValue = field.get(original);
      field.set(clone, fieldValue);
    }
  }
}

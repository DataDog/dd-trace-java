package datadog.trace.core.unsafe;

import java.lang.reflect.Field;
import sun.misc.Unsafe;

final class UnsafeAccess {

  static final Unsafe UNSAFE;
  static final int OBJECT_ARRAY_OFFSET;
  static final int LONG_ARRAY_OFFSET;
  static final int INT_ARRAY_OFFSET;
  static final int BOOLEAN_ARRAY_OFFSET;
  private static final int ARRAY_ELEMENT_SHIFT;

  static {
    // Loading this class should be avoided from JDK9 onwards except in tests
    Unsafe unsafe = null;
    try {
      Field f = Unsafe.class.getDeclaredField("theUnsafe");
      f.setAccessible(true);
      unsafe = (Unsafe) f.get(null);
    } catch (Throwable ignore) {
    }
    UNSAFE = unsafe;
    OBJECT_ARRAY_OFFSET = null == UNSAFE ? 0 : UNSAFE.arrayBaseOffset(Object[].class);
    LONG_ARRAY_OFFSET = null == UNSAFE ? 0 : UNSAFE.arrayBaseOffset(long[].class);
    INT_ARRAY_OFFSET = null == UNSAFE ? 0 : UNSAFE.arrayBaseOffset(int[].class);
    BOOLEAN_ARRAY_OFFSET = null == UNSAFE ? 0 : UNSAFE.arrayBaseOffset(boolean[].class);
    ARRAY_ELEMENT_SHIFT =
        null == UNSAFE ? 0 : Integer.numberOfTrailingZeros(UNSAFE.arrayIndexScale(Object[].class));
  }

  static long objectArrayIndex(int index) {
    return OBJECT_ARRAY_OFFSET + ((long) index << ARRAY_ELEMENT_SHIFT);
  }

  static long booleanArrayIndex(int index) {
    return BOOLEAN_ARRAY_OFFSET + ((long) index);
  }

  static long intArrayIndex(int index) {
    return INT_ARRAY_OFFSET + ((long) index << 2);
  }

  static long longArrayIndex(int index) {
    return LONG_ARRAY_OFFSET + ((long) index << 3);
  }
}

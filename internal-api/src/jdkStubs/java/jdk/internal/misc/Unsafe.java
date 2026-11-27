package jdk.internal.misc;

import java.lang.reflect.Field;

/**
 * Compile-time stand-in for the JDK 9+ class, which the JDK 8 toolchain lacks. It is not packaged:
 * at run time the JDK's own class is used.
 */
public final class Unsafe {

  private Unsafe() {}

  public static Unsafe getUnsafe() {
    throw new UnsupportedOperationException();
  }

  public native long getLong(Object o, long offset);

  public long objectFieldOffset(Field field) {
    throw new UnsupportedOperationException();
  }
}

package datadog.trace.agent.test.utils;

import static datadog.environment.JavaVirtualMachine.isJavaVersionAtLeast;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

public class UnsafeUtils {
  private UnsafeUtils() {}

  private static void setStaticBooleanFieldViaUnsafe(final Field field, final boolean newValue)
      throws Exception {
    final Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    unsafeField.setAccessible(true);
    final sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);

    unsafe.ensureClassInitialized(field.getDeclaringClass());

    final Object staticFieldBase = unsafe.staticFieldBase(field);
    final long staticFieldOffset = unsafe.staticFieldOffset(field);
    unsafe.putBooleanVolatile(staticFieldBase, staticFieldOffset, newValue);
  }

  private static void setStaticFieldViaReflection(final Field field, final Object newValue)
      throws Exception {
    field.setAccessible(true);
    final Field modifiersField = Field.class.getDeclaredField("modifiers");
    modifiersField.setAccessible(true);
    final int origModifiers = field.getModifiers();
    modifiersField.setInt(field, field.getModifiers() & ~Modifier.FINAL);
    field.set(null, newValue);
    modifiersField.setInt(field, origModifiers);
  }

  public static void setStaticBooleanField(final Field field, final boolean newValue)
      throws Exception {
    if (isJavaVersionAtLeast(12)) {
      // since 12 we can't use reflection: http://hg.openjdk.java.net/jdk/jdk/rev/f55a4bc91ef4
      setStaticBooleanFieldViaUnsafe(field, newValue);
    } else {
      setStaticFieldViaReflection(field, newValue);
    }
  }
}

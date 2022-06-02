package datadog.trace.bootstrap.debugger.el;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/** A helper class to resolve a reference path using reflection. */
public final class ReflectiveFieldValueResolver {
  public static Object resolve(Object target, Class<?> targetType, String fldName) {
    Field fld = getField(targetType, fldName);
    if (fld == null) {
      return Values.UNDEFINED_OBJECT;
    }
    try {
      return Modifier.isStatic(fld.getModifiers()) ? fld.get(null) : fld.get(target);
    } catch (IllegalAccessException | IllegalArgumentException ignored) {
      return Values.UNDEFINED_OBJECT;
    }
  }

  private static Field getField(Class<?> container, String name) {
    while (container != null) {
      try {
        Field fld = container.getDeclaredField(name);
        fld.setAccessible(true);
        return fld;
      } catch (NoSuchFieldException ignored) {
        container = container.getSuperclass();
      } catch (SecurityException ignored) {
        return null;
      } catch (Exception ignored) {
        // The only other exception allowed here is InaccessibleObjectException but since we compile
        // against JDK 8 we can not use that type in the exeption handler
        return null;
      }
    }
    return null;
  }
}

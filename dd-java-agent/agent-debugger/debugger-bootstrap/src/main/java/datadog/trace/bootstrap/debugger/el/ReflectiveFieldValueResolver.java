package datadog.trace.bootstrap.debugger.el;

import datadog.trace.bootstrap.debugger.Limits;
import datadog.trace.bootstrap.debugger.Snapshot;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/** A helper class to resolve a reference path using reflection. */
public final class ReflectiveFieldValueResolver {
  public static Object resolveObject(Object target, Class<?> targetType, String fldName) {
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

  public static Snapshot.CapturedValue resolve(
      Snapshot.CapturedValue capturedTarget, Object target, String fldName) {
    if (target == null) {
      return Snapshot.CapturedValue.UNDEFINED;
    }
    Field fld = getField(target.getClass(), fldName);
    if (fld == null) {
      return Snapshot.CapturedValue.UNDEFINED;
    }
    String typeName = fld.getType().getName();
    try {
      Object val = Modifier.isStatic(fld.getModifiers()) ? fld.get(null) : fld.get(target);
      Limits limits = capturedTarget.getLimits();
      return Snapshot.CapturedValue.of(
          fldName,
          typeName,
          val,
          limits.maxReferenceDepth - 1,
          limits.maxCollectionSize,
          limits.maxLength,
          limits.maxFieldCount);
    } catch (IllegalAccessException | IllegalArgumentException ignored) {
      return Snapshot.CapturedValue.UNDEFINED;
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

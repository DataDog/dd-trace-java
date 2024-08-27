package datadog.trace.bootstrap.debugger.el;

import datadog.trace.bootstrap.debugger.CapturedContext;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/** A helper class to resolve a reference path using reflection. */
public class ReflectiveFieldValueResolver {
  public static Object resolve(Object target, Class<?> targetType, String fldName) {
    Field fld = safeGetField(targetType, fldName);
    if (fld == null) {
      return Values.UNDEFINED_OBJECT;
    }
    try {
      return Modifier.isStatic(fld.getModifiers()) ? fld.get(null) : fld.get(target);
    } catch (IllegalAccessException | IllegalArgumentException ignored) {
      return Values.UNDEFINED_OBJECT;
    }
  }

  public static Object getFieldValue(Object target, String fieldName)
      throws NoSuchFieldException, IllegalAccessException {
    return getField(target, fieldName).get(target);
  }

  public static CapturedContext.CapturedValue getFieldAsCapturedValue(
      Object target, String fieldName) {
    if (target == null) {
      return CapturedContext.CapturedValue.of(fieldName, Object.class.getTypeName(), null);
    }
    return getFieldAsCapturedValue(target.getClass(), target, fieldName);
  }

  public static CapturedContext.CapturedValue getFieldAsCapturedValue(
      Class<?> clazz, Object target, String fieldName) {
    Field field;
    try {
      field = getField(clazz, fieldName);
      if (field == null) {
        return CapturedContext.CapturedValue.notCapturedReason(
            fieldName, Object.class.getTypeName(), "Field not found");
      }
    } catch (Exception ex) {
      return CapturedContext.CapturedValue.notCapturedReason(
          fieldName, Object.class.getTypeName(), ex.toString());
    }
    String declaredFieldType = Object.class.getTypeName();
    try {
      declaredFieldType = field.getType().getTypeName();
      Object fieldValue = field.get(target);
      return CapturedContext.CapturedValue.of(fieldName, declaredFieldType, fieldValue);
    } catch (Exception ex) {
      return CapturedContext.CapturedValue.notCapturedReason(
          fieldName, declaredFieldType, ex.toString());
    }
  }

  public static Object getFieldValue(Class<?> targetClass, String fieldName)
      throws IllegalAccessException {
    return safeGetField(targetClass, fieldName).get(null);
  }

  public static long getFieldValueAsLong(Object target, String fieldName)
      throws NoSuchFieldException, IllegalAccessException {
    return getField(target, fieldName).getLong(target);
  }

  public static long getFieldValueAsLong(Class<?> targetClass, String fieldName)
      throws IllegalAccessException {
    return safeGetField(targetClass, fieldName).getLong(null);
  }

  public static int getFieldValueAsInt(Object target, String fieldName)
      throws NoSuchFieldException, IllegalAccessException {
    return getField(target, fieldName).getInt(target);
  }

  public static int getFieldValueAsInt(Class<?> targetClass, String fieldName)
      throws IllegalAccessException {
    return safeGetField(targetClass, fieldName).getInt(null);
  }

  public static double getFieldValueAsDouble(Object target, String fieldName)
      throws NoSuchFieldException, IllegalAccessException {
    return getField(target, fieldName).getDouble(target);
  }

  public static double getFieldValueAsDouble(Class<?> targetClass, String fieldName)
      throws IllegalAccessException {
    return safeGetField(targetClass, fieldName).getDouble(null);
  }

  public static float getFieldValueAsFloat(Object target, String fieldName)
      throws NoSuchFieldException, IllegalAccessException {
    return getField(target, fieldName).getFloat(target);
  }

  public static float getFieldValueAsFloat(Class<?> targetClass, String fieldName)
      throws IllegalAccessException {
    return safeGetField(targetClass, fieldName).getFloat(null);
  }

  public static float getFieldValueAsShort(Object target, String fieldName)
      throws NoSuchFieldException, IllegalAccessException {
    return getField(target, fieldName).getShort(target);
  }

  public static float getFieldValueAsShort(Class<?> targetClass, String fieldName)
      throws IllegalAccessException {
    return safeGetField(targetClass, fieldName).getShort(null);
  }

  public static char getFieldValueAsChar(Object target, String fieldName)
      throws NoSuchFieldException, IllegalAccessException {
    return getField(target, fieldName).getChar(target);
  }

  public static char getFieldValueAsChar(Class<?> targetClass, String fieldName)
      throws IllegalAccessException {
    return safeGetField(targetClass, fieldName).getChar(null);
  }

  public static byte getFieldValueAsByte(Object target, String fieldName)
      throws NoSuchFieldException, IllegalAccessException {
    return getField(target, fieldName).getByte(target);
  }

  public static byte getFieldValueAsByte(Class<?> targetClass, String fieldName)
      throws IllegalAccessException {
    return safeGetField(targetClass, fieldName).getByte(null);
  }

  public static boolean getFieldValueAsBoolean(Object target, String fieldName)
      throws NoSuchFieldException, IllegalAccessException {
    return getField(target, fieldName).getBoolean(target);
  }

  public static boolean getFieldValueAsBoolean(Class<?> targetClass, String fieldName)
      throws IllegalAccessException {
    return safeGetField(targetClass, fieldName).getBoolean(null);
  }

  private static Field getField(Object target, String name) throws NoSuchFieldException {
    if (target == null) {
      throw new NullPointerException();
    }
    Field field = safeGetField(target.getClass(), name);
    if (field == null) {
      throw new NoSuchFieldException(name);
    }
    return field;
  }

  private static Field safeGetField(Class<?> container, String name) {
    try {
      return getField(container, name);
    } catch (SecurityException ignored) {
      return null;
    } catch (Exception e) {
      // The only other exception allowed here is InaccessibleObjectException but since we compile
      // against JDK 8 we can not use that type in the exception handler
      return null;
    }
  }

  private static Field getField(Class<?> container, String name) {
    while (container != null) {
      Field[] declaredFields = container.getDeclaredFields();
      for (int i = 0; i < declaredFields.length; i++) {
        Field declaredField = declaredFields[i];
        if (declaredField.getName().equals(name)) {
          declaredField.setAccessible(true);
          return declaredField;
        }
      }
      container = container.getSuperclass();
    }
    return null;
  }
}

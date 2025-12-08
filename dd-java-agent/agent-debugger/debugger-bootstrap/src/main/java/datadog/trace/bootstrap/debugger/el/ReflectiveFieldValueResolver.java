package datadog.trace.bootstrap.debugger.el;

import static datadog.trace.api.telemetry.LogCollector.EXCLUDE_TELEMETRY;
import static java.lang.invoke.MethodType.methodType;

import datadog.trace.bootstrap.debugger.CapturedContext;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A helper class to resolve a reference path using reflection. */
@SuppressForbidden // Class#forName(String)
public class ReflectiveFieldValueResolver {
  private static final Logger LOGGER = LoggerFactory.getLogger(ReflectiveFieldValueResolver.class);
  // This is a workaround for the fact that Field.trySetAccessible is not available in Java 8
  private static final MethodHandle TRY_SET_ACCESSIBLE;

  static {
    MethodHandle methodHandle = null;
    try {
      MethodHandles.Lookup lookup = MethodHandles.lookup();
      methodHandle = lookup.findVirtual(Field.class, "trySetAccessible", methodType(boolean.class));
    } catch (Exception e) {
      LOGGER.debug(EXCLUDE_TELEMETRY, "Looking up trySetAccessible failed: ", e);
    }
    TRY_SET_ACCESSIBLE = methodHandle;
  }

  // We cannot create a Field instance from scratch to be used as special constant,
  // so need to reflectively access itself
  private static final Field INACCESSIBLE_FIELD;

  static {
    Field field = null;
    try {
      field = ReflectiveFieldValueResolver.class.getDeclaredField("INACCESSIBLE_FIELD");
    } catch (Exception e) {
      LOGGER.debug(EXCLUDE_TELEMETRY, "INACCESSIBLE_FIELD failed: ", e);
    }
    INACCESSIBLE_FIELD = field;
  }

  private static final Class<?> MODULE_CLASS;
  private static final MethodHandle GET_MODULE;

  static {
    MethodHandle methodHandle = null;
    Class<?> moduleClass = null;
    try {
      moduleClass = Class.forName("java.lang.Module");
      MethodHandles.Lookup lookup = MethodHandles.lookup();
      methodHandle = lookup.findVirtual(Class.class, "getModule", methodType(moduleClass));
    } catch (Exception e) {
      LOGGER.debug(EXCLUDE_TELEMETRY, "Looking up getModule failed: ", e);
    }
    GET_MODULE = methodHandle;
    MODULE_CLASS = moduleClass;
  }

  public static Object resolve(Object target, Class<?> targetType, String fldName) {
    Field fld = safeGetField(targetType, fldName);
    if (fld == null) {
      return Values.UNDEFINED_OBJECT;
    }
    if (fld == INACCESSIBLE_FIELD) {
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
      FieldResult fieldResult = getField(clazz, fieldName);
      field = fieldResult.field;
      if (field == null) {
        return CapturedContext.CapturedValue.notCapturedReason(
            fieldName, Object.class.getTypeName(), fieldResult.msg);
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
      return getField(container, name).field;
    } catch (SecurityException ignored) {
      return null;
    } catch (Exception e) {
      // The only other exception allowed here is InaccessibleObjectException but since we compile
      // against JDK 8 we can not use that type in the exception handler
      return null;
    }
  }

  private static class FieldResult {
    final Field field;
    final String msg;

    public FieldResult(Field field, String msg) {
      this.field = field;
      this.msg = msg;
    }
  }

  private static FieldResult getField(Class<?> container, String name) {
    while (container != null) {
      Field[] declaredFields = container.getDeclaredFields();
      for (int i = 0; i < declaredFields.length; i++) {
        Field declaredField = declaredFields[i];
        if (declaredField.getName().equals(name)) {
          if (trySetAccessible(declaredField)) {
            declaredField.setAccessible(true);
          } else {
            return new FieldResult(null, buildInaccessibleMsg(declaredField));
          }
          return new FieldResult(declaredField, null);
        }
      }
      container = container.getSuperclass();
    }
    return new FieldResult(null, "Field not found");
  }

  public static boolean trySetAccessible(Field field) {
    if (TRY_SET_ACCESSIBLE == null) {
      return true;
    }
    try {
      return (boolean) TRY_SET_ACCESSIBLE.invokeExact(field);
    } catch (Throwable e) {
      LOGGER.debug("trySetAccessible call failed: ", e);
      return true;
    }
  }

  public static String buildInaccessibleMsg(Field field) {
    if (MODULE_CLASS != null && GET_MODULE != null) {
      try {
        Object module = GET_MODULE.invoke(field.getDeclaringClass());
        return "Field is not accessible: "
            + module
            + " does not opens/exports to the current module";
      } catch (Throwable ex) {
        LOGGER.debug("buildInaccessibleMsg failed: ", ex);
      }
    }
    return "Field is not accessible";
  }
}

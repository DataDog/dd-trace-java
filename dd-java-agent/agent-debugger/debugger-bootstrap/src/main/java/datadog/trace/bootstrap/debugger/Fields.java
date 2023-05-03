package datadog.trace.bootstrap.debugger;

import java.lang.reflect.Field;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Helper class for processing fields of an instance */
public class Fields {
  public interface ProcessField {
    void accept(Field field, Object value, Limits limits);
  }

  public static void processFields(
      Object o,
      Predicate<Field> filteringIn,
      ProcessField processing,
      BiConsumer<Exception, Field> exHandling,
      Consumer<Field> onMaxFieldCount,
      Limits limits) {
    Class<?> currentClass = o.getClass();
    int processedFieldCount = 0;
    do {
      Field[] fields = currentClass.getDeclaredFields();
      for (Field field : fields) {
        try {
          if (!filteringIn.test(field)) {
            continue;
          }
          field.setAccessible(true);
          Object value = field.get(o);
          processing.accept(field, value, limits);
          processedFieldCount++;
          if (processedFieldCount >= limits.maxFieldCount) {
            onMaxFieldCount.accept(field);
            return;
          }
        } catch (Exception e) {
          exHandling.accept(e, field);
        }
      }
    } while ((currentClass = currentClass.getSuperclass()) != null);
  }

  static boolean isPrimitiveClass(Object obj) {
    Class<?> clazz = obj.getClass();
    if (clazz == Byte.class) {
      return true;
    }
    if (clazz == Short.class) {
      return true;
    }
    if (clazz == Integer.class) {
      return true;
    }
    if (clazz == Character.class) {
      return true;
    }
    if (clazz == Long.class) {
      return true;
    }
    if (clazz == Float.class) {
      return true;
    }
    if (clazz == Double.class) {
      return true;
    }
    if (clazz == Boolean.class) {
      return true;
    }
    // String is treated as primitive here, because we don't want to extract fields of a String,
    // only the actual sequence of chars is relevant
    if (clazz == String.class) {
      return true;
    }
    return false;
  }
}

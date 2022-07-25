package datadog.trace.bootstrap.debugger;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.function.BiConsumer;
import java.util.function.ObjIntConsumer;
import java.util.function.Predicate;

/** Helper class for processing fields of an instance */
public class Fields {
  public interface ProcessField {
    void accept(Field field, Object value, int maxDepth);
  }

  public static void processFields(
      Object o,
      Predicate<Field> filteringIn,
      ProcessField processing,
      BiConsumer<Exception, Field> exHandling,
      ObjIntConsumer<Field> onMaxFieldCount,
      int maxFieldCount,
      int maxDepth) {
    Field[] fields = o.getClass().getDeclaredFields();
    int processedFieldCount = 0;
    for (Field field : fields) {
      try {
        if (!filteringIn.test(field)) {
          continue;
        }
        field.setAccessible(true);
        Object value = field.get(o);
        processing.accept(field, value, maxDepth);
        processedFieldCount++;
        if (processedFieldCount >= maxFieldCount) {
          int total = (int) Arrays.stream(fields).filter(f -> filteringIn.test(f)).count();
          onMaxFieldCount.accept(field, total);
          break;
        }
      } catch (Exception e) {
        exHandling.accept(e, field);
      }
    }
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
    return false;
  }
}

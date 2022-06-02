package datadog.trace.bootstrap.debugger;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.function.BiConsumer;
import java.util.function.ObjIntConsumer;
import java.util.function.Predicate;

/** Helper class for processing fields of an instance */
public class Fields {

  public static void processFields(
      Object o,
      Predicate<Field> filteringIn,
      BiConsumer<Field, Object> processing,
      BiConsumer<Exception, Field> exHandling,
      ObjIntConsumer<Field> onMaxFieldCount,
      int maxFieldCount) {
    Field[] fields = o.getClass().getDeclaredFields();
    int processedFieldCount = 0;
    for (Field field : fields) {
      try {
        if (!filteringIn.test(field)) {
          continue;
        }
        field.setAccessible(true);
        Object value = field.get(o);
        processing.accept(field, value);
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
}

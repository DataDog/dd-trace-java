package datadog.trace.bootstrap.debugger;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.function.BiConsumer;
import java.util.function.ObjIntConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Extracts all fields of an instance to be added into a Snapshot as CapturedValue */
public class FieldExtractor {
  private static final Logger LOG = LoggerFactory.getLogger(FieldExtractor.class);

  private static boolean filterIn(Field field) {
    // Jacoco insert a transient field
    if ("$jacocoData".equals(field.getName()) && Modifier.isTransient(field.getModifiers())) {
      return false;
    }
    // skip constant fields
    if (Modifier.isStatic(field.getModifiers()) && Modifier.isFinal(field.getModifiers())) {
      return false;
    }
    return true;
  }

  public static void extract(
      Object obj,
      Limits limits,
      Fields.ProcessField onField,
      BiConsumer<Exception, Field> exHandling,
      ObjIntConsumer<Field> maxFieldCount) {
    if (obj == null) {
      return;
    }
    if (Fields.isPrimitiveClass(obj)) {
      return;
    }
    Field[] declaredFields = obj.getClass().getDeclaredFields();
    if (declaredFields.length == 0) {
      return;
    }
    Fields.processFields(
        obj,
        FieldExtractor::filterIn,
        onField,
        exHandling,
        maxFieldCount,
        limits.maxFieldCount,
        limits.maxReferenceDepth);
  }
}

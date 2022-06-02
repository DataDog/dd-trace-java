package datadog.trace.bootstrap.debugger;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Extracts all fields of an instance to be added into a Snapshot as CapturedValue */
public class FieldExtractor {
  public static final int DEFAULT_FIELD_DEPTH = -1;
  public static final int DEFAULT_FIELD_COUNT = 20;
  private static final Logger LOG = LoggerFactory.getLogger(ValueConverter.class);

  public static class Limits {
    public final int maxFieldDepth;
    public final int maxFieldCount;

    public static Limits DEFAULT = new Limits(DEFAULT_FIELD_DEPTH, DEFAULT_FIELD_COUNT);

    public Limits(int maxFieldDepth, int maxFieldCount) {

      this.maxFieldDepth = maxFieldDepth;
      this.maxFieldCount = maxFieldCount;
    }
  }

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

  private static void extractField(
      Field field, Object value, Map<String, Snapshot.CapturedValue> results, Limits limits) {
    Snapshot.CapturedValue capturedValue =
        Snapshot.CapturedValue.raw(
            field.getType().getName(),
            value,
            field.getType().isPrimitive() ? -1 : limits.maxFieldDepth - 1,
            limits.maxFieldCount);
    results.put(field.getName(), capturedValue);
  }

  private static void handleExtractException(
      Exception ex, Field field, String className, Map<String, Snapshot.CapturedValue> results) {
    String fieldName = field.getName();
    if (LOG.isDebugEnabled()) {
      LOG.debug("Cannot extract field[{}] from class[{}]", fieldName, className, ex);
    }
    Snapshot.CapturedValue reasonNotCaptured =
        Snapshot.CapturedValue.reasonNotCaptured(
            fieldName, field.getType().getName(), ex.toString());
    results.put(fieldName, reasonNotCaptured);
  }

  private static void onMaxFieldCount(
      Field f, Map<String, Snapshot.CapturedValue> results, int maxFieldCount, int totalFields) {
    String msg =
        String.format(
            "Max %d fields reached, %d fields were not captured",
            maxFieldCount, totalFields - maxFieldCount);
    results.put("@status", Snapshot.CapturedValue.reasonNotCaptured("@status", "", msg));
  }

  public static Map<String, Snapshot.CapturedValue> extract(Object obj, Limits limits) {
    if (obj == null) {
      return Collections.emptyMap();
    }
    if (isPrimitiveClass(obj)) {
      return Collections.emptyMap();
    }
    Field[] declaredFields = obj.getClass().getDeclaredFields();
    if (declaredFields.length == 0) {
      return Collections.emptyMap();
    }
    Map<String, Snapshot.CapturedValue> results = new HashMap<>();
    Fields.processFields(
        obj,
        FieldExtractor::filterIn,
        (f, value) -> extractField(f, value, results, limits),
        (ex, f) -> handleExtractException(ex, f, obj.getClass().getName(), results),
        (f, total) -> onMaxFieldCount(f, results, limits.maxFieldCount, total),
        limits.maxFieldCount);
    return results;
  }

  private static boolean isPrimitiveClass(Object obj) {
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

package datadog.trace.bootstrap.debugger;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
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

  private static void extractField(
      Field field, Object value, Map<String, Snapshot.CapturedValue> results, Limits limits) {
    Map<String, Snapshot.CapturedValue> subFields =
        extract(
            value,
            new Limits(
                limits.maxReferenceDepth - 1,
                limits.maxCollectionSize,
                limits.maxLength,
                limits.maxFieldCount));
    Snapshot.CapturedValue capturedValue =
        Snapshot.CapturedValue.raw(field.getType().getName(), value, limits, subFields, null);
    results.put(field.getName(), capturedValue);
  }

  private static void handleExtractException(
      Exception ex, Field field, String className, Map<String, Snapshot.CapturedValue> results) {
    String fieldName = field.getName();
    if (LOG.isDebugEnabled()) {
      LOG.debug("Cannot extract field[{}] from class[{}]", fieldName, className, ex);
    }
    Snapshot.CapturedValue notCapturedReason =
        Snapshot.CapturedValue.notCapturedReason(
            fieldName, field.getType().getName(), ex.toString());
    results.put(fieldName, notCapturedReason);
  }

  private static void onMaxFieldCount(
      Field f, Map<String, Snapshot.CapturedValue> results, int maxFieldCount, int totalFields) {
    String msg =
        String.format(
            "Max %d fields reached, %d fields were not captured",
            maxFieldCount, totalFields - maxFieldCount);
    results.put("@status", Snapshot.CapturedValue.notCapturedReason("@status", "", msg));
  }

  public static Map<String, Snapshot.CapturedValue> extract(Object obj, Limits limits) {
    if (obj == null) {
      return Collections.emptyMap();
    }
    if (Fields.isPrimitiveClass(obj)) {
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
        (f, value, maxDepth) -> extractField(f, value, results, limits),
        (ex, f) -> handleExtractException(ex, f, obj.getClass().getName(), results),
        (f, total) -> onMaxFieldCount(f, results, limits.maxFieldCount, total),
        limits.maxFieldCount,
        limits.maxReferenceDepth);
    return results;
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

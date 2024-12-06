package datadog.trace.instrumentation.jackson.core;

import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.AgentDataStreamsMonitoring;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.Schema;
import datadog.trace.bootstrap.instrumentation.api.SchemaBuilder;
import datadog.trace.bootstrap.instrumentation.api.SchemaIterator;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

public class SchemaExtractor implements SchemaIterator {

  private Object value;
  public static final String SERIALIZATION = "serialization";
  public static final String DESERIALIZATION = "deserialization";
  private static final String JSON = "json";

  public SchemaExtractor(Object value) {
    this.value = value;
  }

  public static Map<String, Object> getClassStructure(
      Object obj, SchemaBuilder builder, int depth) {
    if (obj == null) {
      throw new IllegalArgumentException("Object cannot be null");
    }
    return getClassStructure(obj.getClass(), builder, obj.getClass().getName(), depth);
  }

  private static Map<String, Object> getClassStructure(
      Class<?> clazz, SchemaBuilder builder, String schemaName, int depth) {
    Map<String, Object> classStructure = new HashMap<>();
    boolean array = false;
    String type = null;
    String format = null;
    String description = null;
    String ref = null;

    for (Field field : clazz.getDeclaredFields()) {
      field.setAccessible(true); // Allow access to private fields

      Class<?> fieldType = field.getType();
      if (fieldType.isArray()) {
        array = true;
        type = getTypeName(fieldType.getComponentType());
      } else if (fieldType.isPrimitive() || isWrapperType(fieldType) || fieldType == String.class) {
        type = getTypeName(fieldType);
      } else {
        type = "object";
        description = "Nested object";
        ref = fieldType.getName();
        getClassStructure(fieldType, builder, fieldType.getName(), depth + 1);
      }

      builder.addProperty(schemaName, field.getName(), array, type, description, ref, format, null);
    }

    return classStructure;
  }

  private static boolean isPrimitiveOrWrapper(Class<?> type) {
    return type.isPrimitive()
        || type == Boolean.class
        || type == Byte.class
        || type == Character.class
        || type == Double.class
        || type == Float.class
        || type == Integer.class
        || type == Long.class
        || type == Short.class;
  }

  public static void attachSchemaOnSpan(Object value, AgentSpan span, String operation) {
    if (value == null || span == null) {
      return;
    }
    AgentDataStreamsMonitoring dsm = AgentTracer.get().getDataStreamsMonitoring();
    span.setTag(DDTags.SCHEMA_TYPE, JSON);
    span.setTag(DDTags.SCHEMA_NAME, value.getClass().getSimpleName());
    span.setTag(DDTags.SCHEMA_OPERATION, operation);

    if (!dsm.canSampleSchema(operation)) {
      return;
    }

    Integer prio = span.forceSamplingDecision();
    if (prio == null || prio <= 0) {
      return;
    }

    int weight = dsm.trySampleSchema(operation);
    if (weight == 0) {
      return;
    }

    Schema schemaData = SchemaExtractor.extractSchemas(value);
    span.setTag(DDTags.SCHEMA_DEFINITION, schemaData.definition);
    span.setTag(DDTags.SCHEMA_WEIGHT, weight);
    span.setTag(DDTags.SCHEMA_ID, schemaData.id);
  }

  public static Schema extractSchemas(Object value) {
    return AgentTracer.get()
        .getDataStreamsMonitoring()
        .getSchema(Object.class.getSimpleName(), new SchemaExtractor(value));
  }

  private static String getTypeName(Class<?> clazz) {
    if (clazz == Boolean.class || clazz == boolean.class) {
      return "boolean";
    } else if (clazz == Byte.class || clazz == byte.class) {
      return "string";
    } else if (clazz == Character.class || clazz == char.class) {
      return "string";
    } else if (clazz == Double.class || clazz == double.class) {
      return "number";
    } else if (clazz == Float.class || clazz == float.class) {
      return "number";
    } else if (clazz == Integer.class || clazz == int.class) {
      return "integer";
    } else if (clazz == Long.class || clazz == long.class) {
      return "integer";
    } else if (clazz == Short.class || clazz == short.class) {
      return "integer";
    } else if (clazz == String.class) {
      return "string";
    } else {
      return "object";
    }
  }

  private static boolean isWrapperType(Class<?> clazz) {
    return clazz == Boolean.class
        || clazz == Byte.class
        || clazz == Character.class
        || clazz == Double.class
        || clazz == Float.class
        || clazz == Integer.class
        || clazz == Long.class
        || clazz == Short.class;
  }

  @Override
  public void iterateOverSchema(SchemaBuilder builder) {
    getClassStructure(value, builder, 0);
  }
}

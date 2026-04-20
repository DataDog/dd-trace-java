package datadog.trace.instrumentation.avro;

import datadog.trace.api.DDTags;
import datadog.trace.api.datastreams.AgentDataStreamsMonitoring;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.Schema;
import datadog.trace.bootstrap.instrumentation.api.SchemaBuilder;
import datadog.trace.bootstrap.instrumentation.api.SchemaIterator;
import java.util.List;
import org.apache.avro.Schema.Field;

public class SchemaExtractor implements SchemaIterator {
  public static final String SERIALIZATION = "serialization";
  public static final String DESERIALIZATION = "deserialization";
  private static final String AVRO = "avro";

  private final org.apache.avro.Schema schema;

  public SchemaExtractor(org.apache.avro.Schema schema) {
    this.schema = schema;
  }

  public static boolean extractProperty(
      Field field, String schemaName, String fieldName, SchemaBuilder builder, int depth) {
    boolean array = false;
    String type = null;
    String format = null;
    String description = null;
    String ref = null;
    List<String> enumValues = null;

    switch (field.schema().getType()) {
      case RECORD:
        type = "#/components/schemas/" + field.schema().getFullName();
        if (!extractSchema(field.schema(), builder, depth)) {
          return false;
        }
        break;
      case ENUM:
        type = "string";
        enumValues = field.schema().getEnumSymbols();
        break;
      case ARRAY:
        array = true;
        type = getType(field.schema().getElementType().getType().getName());
        if (type == "record") {
          type = "#/components/schemas/" + field.schema().getElementType().getFullName();
          if (!extractSchema(field.schema().getElementType(), builder, depth)) {
            return false;
          }
        }
        break;
      case MAP:
        type = "object";
        String keys = "string";
        String values = getType(field.schema().getValueType().getType().getName());
        if (values == "record") {
          values = "#/components/schemas/" + field.schema().getValueType().getFullName();
          if (!extractSchema(field.schema().getValueType(), builder, depth)) {
            return false;
          }
        }
        description = "Map type with " + keys + " keys and " + values + " values";
        break;
      case STRING:
        type = "string";
        break;
      case BYTES:
        type = "string";
        format = "byte";
        break;
      case INT:
        type = "integer";
        format = "int32";
        break;
      case LONG:
        type = "integer";
        format = "int64";
        break;
      case FLOAT:
        type = "number";
        format = "float";
        break;
      case DOUBLE:
        type = "number";
        format = "double";
        break;
      case BOOLEAN:
        type = "boolean";
        break;
      case NULL:
        type = "null";
        break;
      case FIXED:
        type = "string";
        break;
      default:
        type = "string";
        description = "Unknown type";
        break;
    }

    return builder.addProperty(
        schemaName, fieldName, array, type, description, ref, format, enumValues, null);
  }

  public static boolean extractSchema(
      org.apache.avro.Schema schema, SchemaBuilder builder, int depth) {
    depth++;
    String schemaName = schema.getFullName();
    if (!builder.shouldExtractSchema(schemaName, depth)) {
      return false;
    }
    try {
      for (Field field : schema.getFields()) {
        if (!extractProperty(field, schemaName, field.name(), builder, depth)) {
          return false;
        }
      }
    } catch (Exception e) {
      return false;
    }
    return true;
  }

  public static Schema extractSchemas(org.apache.avro.Schema schema) {
    return AgentTracer.get()
        .getDataStreamsMonitoring()
        .getSchema(schema.getFullName(), new SchemaExtractor(schema));
  }

  @Override
  public void iterateOverSchema(SchemaBuilder builder) {
    extractSchema(schema, builder, 0);
  }

  public static void attachSchemaOnSpan(
      org.apache.avro.Schema schema, AgentSpan span, String operation) {
    if (schema == null || span == null) {
      return;
    }
    AgentDataStreamsMonitoring dsm = AgentTracer.get().getDataStreamsMonitoring();

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

    Schema schemaData = SchemaExtractor.extractSchemas(schema);
    span.setTag(DDTags.SCHEMA_TYPE, AVRO);
    span.setTag(DDTags.SCHEMA_NAME, schema.getFullName());
    span.setTag(DDTags.SCHEMA_OPERATION, operation);
    span.setTag(DDTags.SCHEMA_DEFINITION, schemaData.definition);
    span.setTag(DDTags.SCHEMA_WEIGHT, weight);
    span.setTag(DDTags.SCHEMA_ID, schemaData.id);
  }

  private static String getType(String type) {
    switch (type) {
      case "string":
        return "string";
      case "int":
        return "integer";
      case "long":
        return "integer";
      case "float":
        return "number";
      case "double":
        return "number";
      case "boolean":
        return "boolean";
      case "null":
        return "null";
      case "record":
        return "record";
      default:
        return "string";
    }
  }
}

package datadog.trace.instrumentation.avro;

import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.AgentDataStreamsMonitoring;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.Schema;
import datadog.trace.bootstrap.instrumentation.api.SchemaBuilder;
import datadog.trace.bootstrap.instrumentation.api.SchemaIterator;
import java.util.List;
import org.apache.avro.Schema.Field;

public class SchemaExtractor implements SchemaIterator {
  public static final String serialization = "serialization";
  public static final String deserialization = "deserialization";
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
    System.out.println("--------------" + field.schema().getType());
    if (field.schema().getType() == org.apache.avro.Schema.Type.ARRAY) {
      array = true;
    }

    switch (field.schema().getType()) {
      case RECORD:
        ref = "#/components/schemas/" + field.schema().getFullName();
        // Recursively add nested schema
        if (!extractSchema(field.schema(), builder, depth)) {
          return false;
        }
        type = "object";
        break;
      case ENUM:
        type = "string";
        enumValues = field.schema().getEnumSymbols();
        break;
      case ARRAY:
        type = "array";
        ref = "#/components/schemas/" + field.schema().getElementType().getFullName();
        // Recursively handle array element type schema
        if (!extractSchema(field.schema().getElementType(), builder, depth)) {
          return false;
        }
        break;
      case MAP:
        type = "object";
        description = "Map type";
        ref = "#/components/schemas/" + field.schema().getValueType().getFullName();
        // Recursively handle map value type schema
        if (!extractSchema(field.schema().getValueType(), builder, depth)) {
          return false;
        }
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
      default:
        type = "string";
        description = "Unknown type";
        break;
    }

    return builder.addProperty(
        schemaName, fieldName, array, type, description, ref, format, enumValues);
  }

  public static boolean extractSchema(
      org.apache.avro.Schema schema, SchemaBuilder builder, int depth) {
    depth++;
    String schemaName = schema.getFullName();
    if (!builder.shouldExtractSchema(schemaName, depth)) {
      return false;
    }
    System.out.println("+++----------" + schema.getFullName());
    System.out.println("+++-----------" + schema.toString());
    for (Field field : schema.getFields()) {
      if (!extractProperty(field, schemaName, field.name(), builder, depth)) {
        return false;
      }
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
    span.setTag(DDTags.SCHEMA_TYPE, AVRO);
    span.setTag(DDTags.SCHEMA_NAME, schema.getFullName());
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

    Schema schemaData = SchemaExtractor.extractSchemas(schema);
    span.setTag(DDTags.SCHEMA_DEFINITION, schemaData.definition);
    span.setTag(DDTags.SCHEMA_WEIGHT, weight);
    span.setTag(DDTags.SCHEMA_ID, schemaData.id);
  }
}

package datadog.trace.instrumentation.protobuf_java;

import com.google.protobuf.Descriptors;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.SchemaBuilder;
import java.util.List;
import java.util.stream.Collectors;

public class SchemaExtractor {
  private static final int TYPE_DOUBLE = 1;
  private static final int TYPE_FLOAT = 2;
  private static final int TYPE_INT64 = 3;
  private static final int TYPE_UINT64 = 4;
  private static final int TYPE_INT32 = 5;
  private static final int TYPE_FIXED64 = 6;
  private static final int TYPE_FIXED32 = 7;
  private static final int TYPE_BOOL = 8;
  private static final int TYPE_STRING = 9;
  private static final int TYPE_GROUP = 10;
  private static final int TYPE_MESSAGE = 11;
  private static final int TYPE_BYTES = 12;
  private static final int TYPE_UINT32 = 13;
  private static final int TYPE_ENUM = 14;
  private static final int TYPE_SFIXED32 = 15;
  private static final int TYPE_SFIXED64 = 16;
  private static final int TYPE_SINT32 = 17;
  private static final int TYPE_SINT64 = 18;

  public static void extractProperty(
      FieldDescriptor field, String schemaName, String fieldName, SchemaBuilder builder) {
    boolean array = false;
    String type = null;
    String format = null;
    String description = null;
    String ref = null;
    List<String> enumValues = null;
    if (field.isRepeated()) {
      array = true;
    }
    switch (field.getType().toProto().getNumber()) {
      case TYPE_DOUBLE:
        type = "number";
        format = "double";
        break;
      case TYPE_FLOAT:
        type = "number";
        format = "float";
        break;
      case TYPE_INT64:
      case TYPE_SINT64:
        type = "integer";
        format = "int64";
        break;
      case TYPE_UINT64:
        // OpenAPI does not directly support unsigned integers, treated as integers
        type = "integer";
        format = "uint64";
        break;
      case TYPE_INT32:
      case TYPE_SINT32:
        type = "integer";
        format = "int32";
        break;
      case TYPE_FIXED64:
        // Treated as an integer because OpenAPI does not have a fixed64 format.
        type = "integer";
        format = "fixed64";
        break;
      case TYPE_FIXED32:
        type = "integer";
        format = "fixed32";
        break;
      case TYPE_BOOL:
        type = "boolean";
        break;
      case TYPE_STRING:
        type = "string";
        break;
      case TYPE_GROUP:
        // Groups are deprecated and usually represented as nested messages in OpenAPI
        type = "object";
        description = "Group type";
        break;
      case TYPE_MESSAGE:
        ref = "#/components/schemas/" + field.getMessageType().getName();
        // Recursively add nested message schemas
        extractSchema(field.getMessageType(), builder);
        break;
      case TYPE_BYTES:
        type = "string";
        format = "byte";
        break;
      case TYPE_UINT32:
        // As with UINT64, treated as integers or strings because OpenAPI does not directly
        // support unsigned integers
        type = "integer";
        format = "uint32";
        break;
      case TYPE_ENUM:
        type = "string";
        enumValues =
            field.getEnumType().getValues().stream()
                .map(Descriptors.EnumValueDescriptor::getName)
                .collect(Collectors.toList());
        break;
      case TYPE_SFIXED32:
        type = "integer";
        format = "sfixed32";
        break;
      case TYPE_SFIXED64:
        type = "integer";
        format = "sfixed64";
        break;
      default:
        // OpenAPI does not have a direct mapping for unknown types, usually treated as strings or
        // omitted
        type = "string";
        description = "Unknown type";
        break;
    }
    builder.addProperty(schemaName, fieldName, array, type, description, ref, format, enumValues);
  }

  public static void extractSchema(Descriptor descriptor, SchemaBuilder builder) {
    for (FieldDescriptor field : descriptor.getFields()) {
      extractProperty(field, descriptor.getName(), field.getName(), builder);
    }
  }

  public static String extractSchemas(Descriptor descriptor) {
    SchemaBuilder builder = AgentTracer.get().getDataStreamsMonitoring().newSchemaBuilder();
    extractSchema(descriptor, builder);
    return builder.build();
  }
}

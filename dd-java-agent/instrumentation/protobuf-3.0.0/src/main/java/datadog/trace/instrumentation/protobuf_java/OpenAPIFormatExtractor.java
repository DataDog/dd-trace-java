package datadog.trace.instrumentation.protobuf_java;

import com.google.protobuf.Descriptors;
import com.google.protobuf.Descriptors.FieldDescriptor;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class OpenAPIFormatExtractor {
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

  private static String wrapInArray(String serializedType, boolean isArray) {
    return isArray ? "{\"type\": \"array\", \"items\":" + serializedType + "}" : serializedType;
  }

  public static String extractSchema(Descriptors.Descriptor descriptor) {
    List<String> schemas = new ArrayList<>();
    extractDescriptorSchema(descriptor, schemas);
    return "{"
        + "\"openapi\": \"3.0.0\","
        + "\"components\": {\"schemas\": {"
        + String.join(", ", schemas)
        + "}}}";
  }

  private static void extractDescriptorSchema(
      Descriptors.Descriptor descriptor, List<String> schemas) {
    StringBuilder schema = new StringBuilder();
    String schemaName = descriptor.getName();
    schema.append("\"").append(schemaName).append("\": {");
    schema.append("\"type\": \"object\", \"properties\": {");

    boolean firstField = true;
    for (FieldDescriptor field : descriptor.getFields()) {
      if (!firstField) {
        schema.append(", ");
      }
      firstField = false;
      schema.append("\"").append(field.getName()).append("\": ");

      String serializedType;
      switch (field.getType().toProto().getNumber()) {
        case TYPE_DOUBLE:
          serializedType = "{\"type\": \"number\", \"format\": \"double\"}";
          break;
        case TYPE_FLOAT:
          serializedType = "{\"type\": \"number\", \"format\": \"float\"}";
          break;
        case TYPE_INT64:
        case TYPE_SINT64:
          serializedType = "{\"type\": \"integer\", \"format\": \"int64\"}";
          break;
        case TYPE_UINT64:
          // OpenAPI does not directly support unsigned integers, treated as integers
          serializedType = "{\"type\": \"integer\", \"format\": \"uint64\"}";
          break;
        case TYPE_INT32:
        case TYPE_SINT32:
          serializedType = "{\"type\": \"integer\", \"format\": \"int32\"}";
          break;
        case TYPE_FIXED64:
          // Treated as an integer because OpenAPI does not have a fixed64 format.
          serializedType = "{\"type\": \"integer\", \"format\": \"fixed64\"}";
          break;
        case TYPE_FIXED32:
          serializedType = "{\"type\": \"integer\", \"format\": \"fixed32\"}";
          break;
        case TYPE_BOOL:
          serializedType = "{\"type\": \"boolean\"}";
          break;
        case TYPE_STRING:
          serializedType = "{\"type\": \"string\"}";
          break;
        case TYPE_GROUP:
          // Groups are deprecated and usually represented as nested messages in OpenAPI
          serializedType = "{\"type\": \"object\", \"description\": \"Group type\"}";
          break;
        case TYPE_MESSAGE:
          serializedType =
              "{ \"$ref\": \"#/components/schemas/" + field.getMessageType().getName() + "\" }";
          // Recursively add nested message schemas
          extractDescriptorSchema(field.getMessageType(), schemas);
          break;
        case TYPE_BYTES:
          serializedType = "{\"type\": \"string\", \"format\": \"byte\"}";
          break;
        case TYPE_UINT32:
          // As with UINT64, treated as integers or strings because OpenAPI does not directly
          // support unsigned integers
          serializedType = "{\"type\": \"integer\", \"format\": \"uint32\"}";
          break;
        case TYPE_ENUM:
          String jsonFormattedEnumNames =
              field.getEnumType().getValues().stream()
                  .map(Descriptors.EnumValueDescriptor::getName)
                  .map(name -> "\"" + name + "\"")
                  .collect(Collectors.joining(", ", "[", "]"));
          serializedType = "{\"type\": \"string\", \"enum\": " + jsonFormattedEnumNames + "}";
          break;
        case TYPE_SFIXED32:
          serializedType = "{\"type\": \"integer\", \"format\": \"sfixed32\"}";
          break;
        case TYPE_SFIXED64:
          serializedType = "{\"type\": \"integer\", \"format\": \"sfixed64\"}";
          break;
        default:
          // OpenAPI does not have a direct mapping for unknown types, usually treated as strings or
          // omitted
          serializedType = "{\"type\": \"string\", \"description\": \"Unknown type\"}";
          break;
      }
      schema.append(wrapInArray(serializedType, field.isRepeated()));
    }
    schema.append("}}");
    schemas.add(schema.toString());
  }
}

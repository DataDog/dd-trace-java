package datadog.trace.core.otlp.common;

import static datadog.trace.bootstrap.otlp.common.OtlpAttributeVisitor.BOOLEAN_ARRAY_ATTRIBUTE;
import static datadog.trace.bootstrap.otlp.common.OtlpAttributeVisitor.BOOLEAN_ATTRIBUTE;
import static datadog.trace.bootstrap.otlp.common.OtlpAttributeVisitor.DOUBLE_ARRAY_ATTRIBUTE;
import static datadog.trace.bootstrap.otlp.common.OtlpAttributeVisitor.DOUBLE_ATTRIBUTE;
import static datadog.trace.bootstrap.otlp.common.OtlpAttributeVisitor.LONG_ARRAY_ATTRIBUTE;
import static datadog.trace.bootstrap.otlp.common.OtlpAttributeVisitor.LONG_ATTRIBUTE;
import static datadog.trace.bootstrap.otlp.common.OtlpAttributeVisitor.STRING_ARRAY_ATTRIBUTE;
import static datadog.trace.bootstrap.otlp.common.OtlpAttributeVisitor.STRING_ATTRIBUTE;

import datadog.json.JsonWriter;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.otel.common.OtelInstrumentationScope;
import java.util.List;

/** Provides writers for OpenTelemetry's "common.proto" JSON encoding. */
public final class OtlpCommonJson {
  private OtlpCommonJson() {}

  /** Hex-encodes a 128-bit trace id, per the OTLP JSON encoding spec. */
  public static String hexTraceId(DDTraceId traceId) {
    return traceId.toHexString();
  }

  /** Hex-encodes a 64-bit span/parent id, per the OTLP JSON encoding spec. */
  public static String hexSpanId(long spanId) {
    return DDSpanId.toHexStringPadded(spanId);
  }

  public static void writeInstrumentationScope(JsonWriter writer, OtelInstrumentationScope scope) {
    writer.beginObject();
    writer.name("name").value(scope.getName().toString());
    if (scope.getVersion() != null) {
      writer.name("version").value(scope.getVersion().toString());
    }
    writer.endObject();
  }

  /** Writes a scope's {@code "scope"} and optional sibling {@code "schemaUrl"} fields. */
  public static void writeScopeAndSchema(JsonWriter writer, OtelInstrumentationScope scope) {
    writer.name("scope");
    writeInstrumentationScope(writer, scope);
    if (scope.getSchemaUrl() != null) {
      writer.name("schemaUrl").value(scope.getSchemaUrl().toString());
    }
  }

  /** Writes one {@code KeyValue} JSON object: {@code {"key":...,"value":{...}}}. */
  @SuppressWarnings("unchecked")
  public static void writeAttribute(JsonWriter writer, int type, CharSequence key, Object value) {
    writeAttributeKey(writer, key);
    switch (type) {
      case STRING_ATTRIBUTE:
        writeStringValue(writer, (String) value);
        break;
      case BOOLEAN_ATTRIBUTE:
        writeBooleanValue(writer, (boolean) value);
        break;
      case LONG_ATTRIBUTE:
        writeIntValue(writer, ((Number) value).longValue());
        break;
      case DOUBLE_ATTRIBUTE:
        writeDoubleValue(writer, ((Number) value).doubleValue());
        break;
      case STRING_ARRAY_ATTRIBUTE:
        writeArrayValue(writer, STRING_ATTRIBUTE, (List<String>) value);
        break;
      case BOOLEAN_ARRAY_ATTRIBUTE:
        writeArrayValue(writer, BOOLEAN_ATTRIBUTE, (List<Boolean>) value);
        break;
      case LONG_ARRAY_ATTRIBUTE:
        writeArrayValue(writer, LONG_ATTRIBUTE, (List<? extends Number>) value);
        break;
      case DOUBLE_ARRAY_ATTRIBUTE:
        writeArrayValue(writer, DOUBLE_ATTRIBUTE, (List<? extends Number>) value);
        break;
      default:
        throw new IllegalArgumentException("Unknown attribute type: " + type);
    }
    writer.endObject();
  }

  public static void writeAttribute(JsonWriter writer, UTF8BytesString key, String value) {
    writeAttribute(writer, STRING_ATTRIBUTE, key.toString(), value);
  }

  public static void writeAttribute(JsonWriter writer, UTF8BytesString key, long value) {
    writeAttributeKey(writer, key);
    writeIntValue(writer, value);
    writer.endObject();
  }

  private static void writeAttributeKey(JsonWriter writer, CharSequence key) {
    writer.beginObject();
    writer.name("key").value(key.toString());
    writer.name("value");
  }

  private static void writeArrayValue(JsonWriter writer, int elementType, List<?> values) {
    writer.beginObject();
    writer.name("arrayValue").beginObject();
    writer.name("values").beginArray();
    for (Object value : values) {
      writeAnyValue(writer, elementType, value);
    }
    writer.endArray();
    writer.endObject();
    writer.endObject();
  }

  private static void writeAnyValue(JsonWriter writer, int type, Object value) {
    switch (type) {
      case STRING_ATTRIBUTE:
        writeStringValue(writer, (String) value);
        break;
      case BOOLEAN_ATTRIBUTE:
        writeBooleanValue(writer, (boolean) value);
        break;
      case LONG_ATTRIBUTE:
        writeIntValue(writer, ((Number) value).longValue());
        break;
      case DOUBLE_ATTRIBUTE:
        writeDoubleValue(writer, ((Number) value).doubleValue());
        break;
      default:
        throw new IllegalArgumentException("Unknown attribute type: " + type);
    }
  }

  private static void writeStringValue(JsonWriter writer, String value) {
    writer.beginObject().name("stringValue").value(value).endObject();
  }

  private static void writeBooleanValue(JsonWriter writer, boolean value) {
    writer.beginObject().name("boolValue").value(value).endObject();
  }

  private static void writeIntValue(JsonWriter writer, long value) {
    // int64 fields are encoded as decimal strings, per the OTLP JSON encoding spec
    writer.beginObject().name("intValue").value(Long.toString(value)).endObject();
  }

  private static void writeDoubleValue(JsonWriter writer, double value) {
    writer.beginObject().name("doubleValue");
    writeDouble(writer, value);
    writer.endObject();
  }

  /** Writes a double per the OTLP/ProtoJSON encoding spec: NaN/Infinity as JSON strings. */
  public static void writeDouble(JsonWriter writer, double value) {
    if (Double.isNaN(value)) {
      writer.value("NaN");
    } else if (Double.isInfinite(value)) {
      writer.value(value > 0 ? "Infinity" : "-Infinity");
    } else {
      writer.value(value);
    }
  }
}

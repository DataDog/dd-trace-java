package datadog.trace.test.agent.decoder.v1.raw;

import datadog.trace.test.agent.decoder.DecodedSpan;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.msgpack.core.MessageUnpacker;
import org.msgpack.value.ValueType;

/**
 * SpanV1 decodes spans in V1.0 format.
 *
 * <p>V1.0 format differences from V0.4:
 *
 * <ul>
 *   <li>Uses integer field IDs instead of string keys
 *   <li>Streaming string encoding (string table)
 *   <li>Attributes as flat array of triplets (key, type, value) instead of separate meta/metrics
 *   <li>Error as boolean instead of int
 *   <li>Promoted fields (env, version, component) as separate span fields
 *   <li>No traceId in span (it's at trace chunk level in full spec)
 * </ul>
 */
public class SpanV1 implements DecodedSpan {
  // Span field IDs (from TraceMapperV1)
  static final int SPAN_FIELD_SERVICE = 1;
  static final int SPAN_FIELD_NAME = 2;
  static final int SPAN_FIELD_RESOURCE = 3;
  static final int SPAN_FIELD_SPAN_ID = 4;
  static final int SPAN_FIELD_PARENT_ID = 5;
  static final int SPAN_FIELD_START = 6;
  static final int SPAN_FIELD_DURATION = 7;
  static final int SPAN_FIELD_ERROR = 8;
  static final int SPAN_FIELD_ATTRIBUTES = 9;
  static final int SPAN_FIELD_TYPE = 10;
  static final int SPAN_FIELD_SPAN_LINKS = 11;
  static final int SPAN_FIELD_SPAN_EVENTS = 12;
  static final int SPAN_FIELD_ENV = 13;
  static final int SPAN_FIELD_VERSION = 14;
  static final int SPAN_FIELD_COMPONENT = 15;
  static final int SPAN_FIELD_SPAN_KIND = 16;

  // Attribute value types
  static final int STRING_VALUE_TYPE = 1;
  static final int BOOL_VALUE_TYPE = 2;
  static final int FLOAT_VALUE_TYPE = 3;
  static final int INT_VALUE_TYPE = 4;
  static final int BYTES_VALUE_TYPE = 5;
  static final int ARRAY_VALUE_TYPE = 6;
  static final int KEY_VALUE_LIST_TYPE = 7;

  /**
   * Unpacks an array of spans from the unpacker.
   *
   * @param unpacker the message unpacker
   * @param stringTable the shared string table for streaming string decoding
   * @return array of decoded spans
   */
  static DecodedSpan[] unpackSpans(MessageUnpacker unpacker, List<String> stringTable) {
    try {
      int size = unpacker.unpackArrayHeader();
      if (size < 0) {
        throw new IllegalArgumentException("Negative span array size " + size);
      }
      DecodedSpan[] spans = new DecodedSpan[size];
      for (int i = 0; i < size; i++) {
        spans[i] = unpack(unpacker, stringTable);
      }
      return spans;
    } catch (Throwable t) {
      if (t instanceof RuntimeException) {
        throw (RuntimeException) t;
      } else {
        throw new IllegalArgumentException(t);
      }
    }
  }

  /**
   * Unpacks a single span from the unpacker.
   *
   * @param unpacker the message unpacker
   * @param stringTable the shared string table for streaming string decoding
   * @return the decoded span
   */
  static SpanV1 unpack(MessageUnpacker unpacker, List<String> stringTable) {
    try {
      int mapSize = unpacker.unpackMapHeader();

      String service = "";
      String name = "";
      String resource = "";
      long spanId = 0;
      long parentId = 0;
      long start = 0;
      long duration = 0;
      int error = 0;
      String type = "";
      String env = "";
      String version = "";
      String component = "";
      int spanKind = 0;
      Map<String, String> meta = new HashMap<>();
      Map<String, Number> metrics = new HashMap<>();

      for (int i = 0; i < mapSize; i++) {
        int fieldId = unpacker.unpackInt();

        switch (fieldId) {
          case SPAN_FIELD_SERVICE:
            service = unpackStreamingString(unpacker, stringTable);
            break;
          case SPAN_FIELD_NAME:
            name = unpackStreamingString(unpacker, stringTable);
            break;
          case SPAN_FIELD_RESOURCE:
            resource = unpackStreamingString(unpacker, stringTable);
            break;
          case SPAN_FIELD_SPAN_ID:
            spanId = unpacker.unpackLong();
            break;
          case SPAN_FIELD_PARENT_ID:
            parentId = unpacker.unpackLong();
            break;
          case SPAN_FIELD_START:
            start = unpacker.unpackLong();
            break;
          case SPAN_FIELD_DURATION:
            duration = unpacker.unpackLong();
            break;
          case SPAN_FIELD_ERROR:
            // V1.0 uses boolean for error
            error = unpacker.unpackBoolean() ? 1 : 0;
            break;
          case SPAN_FIELD_ATTRIBUTES:
            unpackAttributes(unpacker, stringTable, meta, metrics);
            break;
          case SPAN_FIELD_TYPE:
            type = unpackStreamingString(unpacker, stringTable);
            break;
          case SPAN_FIELD_SPAN_LINKS:
            // Skip span links for now
            unpacker.skipValue();
            break;
          case SPAN_FIELD_SPAN_EVENTS:
            // Skip span events for now
            unpacker.skipValue();
            break;
          case SPAN_FIELD_ENV:
            env = unpackStreamingString(unpacker, stringTable);
            break;
          case SPAN_FIELD_VERSION:
            version = unpackStreamingString(unpacker, stringTable);
            break;
          case SPAN_FIELD_COMPONENT:
            component = unpackStreamingString(unpacker, stringTable);
            break;
          case SPAN_FIELD_SPAN_KIND:
            spanKind = unpacker.unpackInt();
            break;
          default:
            // Skip unknown fields
            unpacker.skipValue();
            break;
        }
      }

      // Add promoted fields to meta if non-empty
      if (env != null && !env.isEmpty()) {
        meta.put("env", env);
      }
      if (version != null && !version.isEmpty()) {
        meta.put("version", version);
      }
      if (component != null && !component.isEmpty()) {
        meta.put("component", component);
      }
      if (spanKind > 0) {
        meta.put("span.kind", getSpanKindString(spanKind));
      }

      // Note: traceId is not present in V1.0 span format (it's at trace chunk level)
      // We use 0 as a placeholder
      long traceId = 0;

      return new SpanV1(
          service, name, resource, traceId, spanId, parentId, start, duration, error, type, metrics,
          meta, null);
    } catch (Throwable t) {
      if (t instanceof RuntimeException) {
        throw (RuntimeException) t;
      } else {
        throw new IllegalArgumentException(t);
      }
    }
  }

  /**
   * Unpacks a streaming string value.
   *
   * <p>In V1.0 format, strings use streaming encoding:
   *
   * <ul>
   *   <li>First occurrence: actual string is written and added to table
   *   <li>Subsequent occurrences: integer index is written
   *   <li>Index 0 is reserved for empty string
   * </ul>
   *
   * @param unpacker the message unpacker
   * @param stringTable the string table to use/update
   * @return the decoded string
   * @throws IOException if unpacking fails
   */
  private static String unpackStreamingString(MessageUnpacker unpacker, List<String> stringTable)
      throws IOException {
    ValueType valueType = unpacker.getNextFormat().getValueType();
    if (valueType == ValueType.INTEGER) {
      // Reference to existing string in table
      int index = unpacker.unpackInt();
      if (index < 0 || index >= stringTable.size()) {
        throw new IllegalArgumentException(
            "Invalid string table index: " + index + ", table size: " + stringTable.size());
      }
      return stringTable.get(index);
    } else if (valueType == ValueType.STRING) {
      // New string, add to table
      String str = unpacker.unpackString();
      stringTable.add(str);
      return str;
    } else {
      throw new IllegalArgumentException(
          "Expected string or integer for streaming string, got: " + valueType);
    }
  }

  /**
   * Unpacks attributes array into meta and metrics maps.
   *
   * <p>Attributes are encoded as a flat array of triplets: (key, type, value)
   *
   * @param unpacker the message unpacker
   * @param stringTable the string table
   * @param meta output map for string attributes
   * @param metrics output map for numeric attributes
   * @throws IOException if unpacking fails
   */
  private static void unpackAttributes(
      MessageUnpacker unpacker,
      List<String> stringTable,
      Map<String, String> meta,
      Map<String, Number> metrics)
      throws IOException {
    int arraySize = unpacker.unpackArrayHeader();
    // Array contains triplets (key, type, value), so size must be divisible by 3
    if (arraySize % 3 != 0) {
      throw new IllegalArgumentException(
          "Attributes array size must be divisible by 3, got: " + arraySize);
    }

    int tripletCount = arraySize / 3;
    for (int i = 0; i < tripletCount; i++) {
      // Key is a streaming string
      String key = unpackStreamingString(unpacker, stringTable);
      // Type is an integer
      int valueType = unpacker.unpackInt();
      // Value depends on type
      switch (valueType) {
        case STRING_VALUE_TYPE:
          String strValue = unpackStreamingString(unpacker, stringTable);
          meta.put(key, strValue);
          break;
        case BOOL_VALUE_TYPE:
          boolean boolValue = unpacker.unpackBoolean();
          // Store booleans as strings in meta for compatibility
          meta.put(key, String.valueOf(boolValue));
          break;
        case FLOAT_VALUE_TYPE:
          double floatValue = unpacker.unpackDouble();
          metrics.put(key, floatValue);
          break;
        case INT_VALUE_TYPE:
          long intValue = unpacker.unpackLong();
          metrics.put(key, intValue);
          break;
        case BYTES_VALUE_TYPE:
          // Skip binary data
          unpacker.skipValue();
          break;
        case ARRAY_VALUE_TYPE:
          // Skip array values
          unpacker.skipValue();
          break;
        case KEY_VALUE_LIST_TYPE:
          // Skip key-value list
          unpacker.skipValue();
          break;
        default:
          throw new IllegalArgumentException("Unknown attribute value type: " + valueType);
      }
    }
  }

  /**
   * Converts span kind integer to string representation.
   *
   * @param spanKind the OTEL span kind value
   * @return the string representation
   */
  private static String getSpanKindString(int spanKind) {
    switch (spanKind) {
      case 1:
        return "internal";
      case 2:
        return "server";
      case 3:
        return "client";
      case 4:
        return "producer";
      case 5:
        return "consumer";
      default:
        return "internal";
    }
  }

  private final String service;
  private final String name;
  private final String resource;
  private final long traceId;
  private final long spanId;
  private final long parentId;
  private final long start;
  private final long duration;
  private final int error;
  private final Map<String, String> meta;
  private final Map<String, Object> metaStruct;
  private final Map<String, Number> metrics;
  private final String type;

  public SpanV1(
      String service,
      String name,
      String resource,
      long traceId,
      long spanId,
      long parentId,
      long start,
      long duration,
      int error,
      String type,
      Map<String, Number> metrics,
      Map<String, String> meta,
      Map<String, Object> metaStruct) {
    this.service = service;
    this.name = name;
    this.resource = resource;
    this.traceId = traceId;
    this.spanId = spanId;
    this.parentId = parentId;
    this.start = start;
    this.duration = duration;
    this.error = error;
    this.meta = Collections.unmodifiableMap(meta);
    this.metaStruct = metaStruct == null ? null : Collections.unmodifiableMap(metaStruct);
    this.metrics = Collections.unmodifiableMap(metrics);
    this.type = type;
  }

  @Override
  public String getService() {
    return service;
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public String getResource() {
    return resource;
  }

  @Override
  public long getTraceId() {
    return traceId;
  }

  @Override
  public long getSpanId() {
    return spanId;
  }

  @Override
  public long getParentId() {
    return parentId;
  }

  @Override
  public long getStart() {
    return start;
  }

  @Override
  public long getDuration() {
    return duration;
  }

  @Override
  public int getError() {
    return error;
  }

  @Override
  public Map<String, String> getMeta() {
    return meta;
  }

  @Override
  public Map<String, Object> getMetaStruct() {
    return metaStruct;
  }

  @Override
  public Map<String, Number> getMetrics() {
    return metrics;
  }

  @Override
  public String getType() {
    return type;
  }

  @Override
  public String toString() {
    return "SpanV1{"
        + "service='"
        + service
        + '\''
        + ", name='"
        + name
        + '\''
        + ", resource='"
        + resource
        + '\''
        + ", traceId="
        + traceId
        + ", spanId="
        + spanId
        + ", parentId="
        + parentId
        + ", start="
        + start
        + ", duration="
        + duration
        + ", error="
        + error
        + ", meta="
        + meta
        + ", metaStruct="
        + metaStruct
        + ", metrics="
        + metrics
        + ", type='"
        + type
        + '\''
        + '}';
  }
}

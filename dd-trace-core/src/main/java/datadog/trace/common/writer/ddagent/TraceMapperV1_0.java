package datadog.trace.common.writer.ddagent;

import static datadog.communication.http.OkHttpUtils.msgpackRequestBodyOf;

import datadog.communication.serialization.Writable;
import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.common.writer.Payload;
import datadog.trace.core.CoreSpan;
import datadog.trace.core.Metadata;
import datadog.trace.core.MetadataConsumer;
import datadog.trace.core.PendingTrace;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import okhttp3.RequestBody;

/**
 * TraceMapperV1_0 implements the V1 trace payload format as specified in the V1 Efficient Trace
 * Payload Protocol RFC.
 *
 * <p>Key differences from V0.4/V0.5:
 *
 * <ul>
 *   <li>Uses map with integer field IDs instead of string keys
 *   <li>String table (streaming strings) for efficient string encoding
 *   <li>Attributes encoded as arrays (key, type, value triplets)
 *   <li>Trace chunks as a separate structure with 128-bit trace ID
 *   <li>Promoted fields (env, version, component, span.kind) as separate span fields
 *   <li>Error as boolean instead of int
 *   <li>SpanKind as uint32 matching OTEL spec values
 * </ul>
 */
public final class TraceMapperV1_0 implements TraceMapper {

  // Attribute value types (from V1 spec)
  static final int STRING_VALUE_TYPE = 1;
  static final int BOOL_VALUE_TYPE = 2;
  static final int FLOAT_VALUE_TYPE = 3;
  static final int INT_VALUE_TYPE = 4;
  static final int BYTES_VALUE_TYPE = 5;
  static final int ARRAY_VALUE_TYPE = 6;
  static final int KEY_VALUE_LIST_TYPE = 7;

  // Span kind OTEL values
  static final int SPAN_KIND_UNSPECIFIED = 0;
  static final int SPAN_KIND_INTERNAL = 1;
  static final int SPAN_KIND_SERVER = 2;
  static final int SPAN_KIND_CLIENT = 3;
  static final int SPAN_KIND_PRODUCER = 4;
  static final int SPAN_KIND_CONSUMER = 5;

  // Tracer Payload field IDs
  static final int FIELD_CONTAINER_ID = 2;
  static final int FIELD_LANGUAGE_NAME = 3;
  static final int FIELD_LANGUAGE_VERSION = 4;
  static final int FIELD_TRACER_VERSION = 5;
  static final int FIELD_RUNTIME_ID = 6;
  static final int FIELD_ENV = 7;
  static final int FIELD_HOSTNAME = 8;
  static final int FIELD_APP_VERSION = 9;
  static final int FIELD_PAYLOAD_ATTRIBUTES = 10;
  static final int FIELD_CHUNKS = 11;

  // TraceChunk field IDs
  static final int CHUNK_FIELD_PRIORITY = 1;
  static final int CHUNK_FIELD_ORIGIN = 2;
  static final int CHUNK_FIELD_ATTRIBUTES = 3;
  static final int CHUNK_FIELD_SPANS = 4;
  static final int CHUNK_FIELD_DROPPED_TRACE = 5;
  static final int CHUNK_FIELD_TRACE_ID = 6;
  static final int CHUNK_FIELD_SAMPLING_MECHANISM = 7;

  // Span field IDs
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

  // SpanLink field IDs
  static final int LINK_FIELD_TRACE_ID = 1;
  static final int LINK_FIELD_SPAN_ID = 2;
  static final int LINK_FIELD_ATTRIBUTES = 3;
  static final int LINK_FIELD_TRACESTATE = 4;
  static final int LINK_FIELD_FLAGS = 5;

  // Promoted tag names that should not appear in attributes
  private static final String TAG_ENV = Tags.ENV;
  private static final String TAG_VERSION = Tags.DD_VERSION;
  private static final String TAG_COMPONENT = Tags.COMPONENT;
  private static final String TAG_SPAN_KIND = Tags.SPAN_KIND;

  // Decision maker tag key
  private static final String KEY_DECISION_MAKER = "_dd.p.dm";

  private final int size;
  private final StringTable stringTable;
  private final MetaWriter metaWriter;
  private boolean firstSpanWritten;

  public TraceMapperV1_0(int size) {
    this.size = size;
    this.stringTable = new StringTable();
    this.metaWriter = new MetaWriter();
  }

  public TraceMapperV1_0() {
    this(5 << 20);
  }

  @Override
  public void map(List<? extends CoreSpan<?>> trace, final Writable writable) {
    // Write array header for the trace (list of spans)
    writable.startArray(trace.size());

    for (int i = 0; i < trace.size(); i++) {
      final CoreSpan<?> span = trace.get(i);

      // Count fields for span map (we always write all 16 fields)
      writable.startMap(16);

      // Field 1: service
      writable.writeInt(SPAN_FIELD_SERVICE);
      writeStreamingString(writable, span.getServiceName());

      // Field 2: name
      writable.writeInt(SPAN_FIELD_NAME);
      writeStreamingString(writable, span.getOperationName());

      // Field 3: resource
      writable.writeInt(SPAN_FIELD_RESOURCE);
      writeStreamingString(writable, span.getResourceName());

      // Field 4: spanID
      writable.writeInt(SPAN_FIELD_SPAN_ID);
      writable.writeUnsignedLong(span.getSpanId());

      // Field 5: parentID
      writable.writeInt(SPAN_FIELD_PARENT_ID);
      writable.writeUnsignedLong(span.getParentId());

      // Field 6: start
      writable.writeInt(SPAN_FIELD_START);
      writable.writeLong(span.getStartTime());

      // Field 7: duration
      writable.writeInt(SPAN_FIELD_DURATION);
      writable.writeLong(PendingTrace.getDurationNano(span));

      // Field 8: error (boolean in V1)
      writable.writeInt(SPAN_FIELD_ERROR);
      writable.writeBoolean(span.getError() != 0);

      // Field 9: attributes (combined meta + metrics)
      // Fields 13-16: promoted fields (env, version, component, spanKind)
      span.processTagsAndBaggage(
          metaWriter
              .withWritable(writable)
              .forSpan(i == 0, i == trace.size() - 1, !firstSpanWritten));

      // Field 10: spanType
      writable.writeInt(SPAN_FIELD_TYPE);
      writeStreamingString(writable, span.getType());

      // Field 11: spanLinks (empty array for now - could be extended)
      writable.writeInt(SPAN_FIELD_SPAN_LINKS);
      writable.startArray(0);

      // Field 12: spanEvents (empty array for now - could be extended)
      writable.writeInt(SPAN_FIELD_SPAN_EVENTS);
      writable.startArray(0);

      firstSpanWritten = true;
    }
  }

  /** Writes a string using the streaming string table encoding. */
  private void writeStreamingString(Writable writable, CharSequence value) {
    String str = value == null ? "" : value.toString();
    Integer index = stringTable.get(str);
    if (index != null) {
      // String already in table, write index
      writable.writeInt(index);
    } else {
      // New string, write it directly and add to table
      writable.writeString(str, null);
      stringTable.add(str);
    }
  }

  /** Converts a span kind string to its OTEL uint32 value. */
  static int getSpanKindValue(String spanKind) {
    if (spanKind == null) {
      return SPAN_KIND_INTERNAL;
    }
    switch (spanKind) {
      case Tags.SPAN_KIND_INTERNAL:
        return SPAN_KIND_INTERNAL;
      case Tags.SPAN_KIND_SERVER:
        return SPAN_KIND_SERVER;
      case Tags.SPAN_KIND_CLIENT:
        return SPAN_KIND_CLIENT;
      case Tags.SPAN_KIND_PRODUCER:
        return SPAN_KIND_PRODUCER;
      case Tags.SPAN_KIND_CONSUMER:
        return SPAN_KIND_CONSUMER;
      default:
        return SPAN_KIND_INTERNAL;
    }
  }

  @Override
  public Payload newPayload() {
    return new PayloadV1_0(stringTable);
  }

  @Override
  public int messageBufferSize() {
    return size;
  }

  @Override
  public void reset() {
    stringTable.clear();
    firstSpanWritten = false;
  }

  @Override
  public String endpoint() {
    return "v1.0";
  }

  /** String table for streaming string encoding. */
  static class StringTable {
    private final Map<String, Integer> indices = new HashMap<>();
    private int nextIndex = 1; // Index 0 is reserved for empty string

    StringTable() {
      indices.put("", 0);
    }

    Integer get(String str) {
      return indices.get(str);
    }

    void add(String str) {
      if (!indices.containsKey(str)) {
        indices.put(str, nextIndex++);
      }
    }

    void clear() {
      indices.clear();
      indices.put("", 0);
      nextIndex = 1;
    }

    int size() {
      return nextIndex;
    }
  }

  /** MetaWriter for V1.0 format that writes attributes and promoted fields. */
  private final class MetaWriter implements MetadataConsumer {
    private Writable writable;
    private boolean firstSpanInTrace;
    private boolean lastSpanInTrace;
    private boolean firstSpanInPayload;

    MetaWriter withWritable(Writable writable) {
      this.writable = writable;
      return this;
    }

    MetaWriter forSpan(boolean firstInTrace, boolean lastInTrace, boolean firstInPayload) {
      this.firstSpanInTrace = firstInTrace;
      this.lastSpanInTrace = lastInTrace;
      this.firstSpanInPayload = firstInPayload;
      return this;
    }

    @Override
    public void accept(Metadata metadata) {
      // Extract promoted fields
      String env = null;
      String version = null;
      String component = null;
      String spanKind = null;

      // Count attributes (excluding promoted fields)
      int attributeCount = 0;

      final boolean writeSamplingPriority = firstSpanInTrace || lastSpanInTrace;
      final UTF8BytesString processTags = firstSpanInPayload ? metadata.processTags() : null;

      // Count non-promoted attributes
      for (Map.Entry<String, Object> entry : metadata.getTags().entrySet()) {
        String key = entry.getKey();
        Object value = entry.getValue();

        if (TAG_ENV.equals(key)) {
          env = String.valueOf(value);
        } else if (TAG_VERSION.equals(key)) {
          version = String.valueOf(value);
        } else if (TAG_COMPONENT.equals(key)) {
          component = String.valueOf(value);
        } else if (TAG_SPAN_KIND.equals(key)) {
          spanKind = String.valueOf(value);
        } else if (!(value instanceof Map)) {
          attributeCount++;
        } else {
          attributeCount += getFlatMapSize((Map<String, Object>) value);
        }
      }

      // Add baggage items
      attributeCount += metadata.getBaggage().size();

      // Add thread name and ID
      attributeCount += 2;

      // Add HTTP status code if present
      if (metadata.getHttpStatusCode() != null) {
        attributeCount++;
      }

      // Add origin if present
      if (metadata.getOrigin() != null) {
        attributeCount++;
      }

      // Add process tags if present
      if (processTags != null) {
        attributeCount++;
      }

      // Add sampling priority if needed
      if (writeSamplingPriority && metadata.hasSamplingPriority()) {
        attributeCount++;
      }

      // Add measured metric if needed
      if (metadata.measured()) {
        attributeCount++;
      }

      // Add top level metric if needed
      if (metadata.topLevel()) {
        attributeCount++;
      }

      // Add long running version if needed
      if (metadata.longRunningVersion() != 0) {
        attributeCount++;
      }

      // Write Field 9: attributes
      writable.writeInt(SPAN_FIELD_ATTRIBUTES);
      // Attributes are encoded as an array with triplets (key, type, value)
      writable.startArray(attributeCount * 3);

      // Write baggage
      for (Map.Entry<String, String> entry : metadata.getBaggage().entrySet()) {
        writeAttribute(entry.getKey(), entry.getValue(), STRING_VALUE_TYPE);
      }

      // Write thread name
      writeAttribute(DDTags.THREAD_NAME, metadata.getThreadName().toString(), STRING_VALUE_TYPE);

      // Write thread ID
      writeAttribute(DDTags.THREAD_ID, metadata.getThreadId(), FLOAT_VALUE_TYPE);

      // Write HTTP status code if present
      if (metadata.getHttpStatusCode() != null) {
        writeAttribute(
            Tags.HTTP_STATUS, metadata.getHttpStatusCode().toString(), STRING_VALUE_TYPE);
      }

      // Write origin if present
      if (metadata.getOrigin() != null) {
        writeAttribute(DDTags.ORIGIN_KEY, metadata.getOrigin().toString(), STRING_VALUE_TYPE);
      }

      // Write process tags if present
      if (processTags != null) {
        writeAttribute(DDTags.PROCESS_TAGS, processTags.toString(), STRING_VALUE_TYPE);
      }

      // Write sampling priority if needed
      if (writeSamplingPriority && metadata.hasSamplingPriority()) {
        writeAttribute("_sampling_priority_v1", metadata.samplingPriority(), FLOAT_VALUE_TYPE);
      }

      // Write measured metric if needed
      if (metadata.measured()) {
        writeAttribute(InstrumentationTags.DD_MEASURED.toString(), 1, FLOAT_VALUE_TYPE);
      }

      // Write top level metric if needed
      if (metadata.topLevel()) {
        writeAttribute(InstrumentationTags.DD_TOP_LEVEL.toString(), 1, FLOAT_VALUE_TYPE);
      }

      // Write long running version if needed
      if (metadata.longRunningVersion() != 0) {
        if (metadata.longRunningVersion() > 0) {
          writeAttribute(
              InstrumentationTags.DD_PARTIAL_VERSION.toString(),
              metadata.longRunningVersion(),
              FLOAT_VALUE_TYPE);
        } else {
          writeAttribute(InstrumentationTags.DD_WAS_LONG_RUNNING.toString(), 1, FLOAT_VALUE_TYPE);
        }
      }

      // Write non-promoted tags
      for (Map.Entry<String, Object> entry : metadata.getTags().entrySet()) {
        String key = entry.getKey();
        Object value = entry.getValue();

        // Skip promoted fields
        if (TAG_ENV.equals(key)
            || TAG_VERSION.equals(key)
            || TAG_COMPONENT.equals(key)
            || TAG_SPAN_KIND.equals(key)) {
          continue;
        }

        if (value instanceof Map) {
          writeFlatMap(key, (Map<String, Object>) value);
        } else if (value instanceof Number) {
          writeAttribute(key, ((Number) value).doubleValue(), FLOAT_VALUE_TYPE);
        } else if (value instanceof Boolean) {
          writeAttribute(key, (Boolean) value, BOOL_VALUE_TYPE);
        } else {
          writeAttribute(key, String.valueOf(value), STRING_VALUE_TYPE);
        }
      }

      // Write promoted fields (13-16)

      // Field 13: env
      writable.writeInt(SPAN_FIELD_ENV);
      writeStreamingString(writable, env != null ? env : "");

      // Field 14: version
      writable.writeInt(SPAN_FIELD_VERSION);
      writeStreamingString(writable, version != null ? version : "");

      // Field 15: component
      writable.writeInt(SPAN_FIELD_COMPONENT);
      writeStreamingString(writable, component != null ? component : "");

      // Field 16: spanKind (uint32)
      writable.writeInt(SPAN_FIELD_SPAN_KIND);
      writable.writeInt(getSpanKindValue(spanKind));
    }

    private void writeAttribute(String key, String value, int valueType) {
      writeStreamingString(writable, key);
      writable.writeInt(valueType);
      writeStreamingString(writable, value);
    }

    private void writeAttribute(String key, long value, int valueType) {
      writeStreamingString(writable, key);
      writable.writeInt(valueType);
      // For FLOAT_VALUE_TYPE, write as double for consistency
      if (valueType == FLOAT_VALUE_TYPE) {
        writable.writeDouble((double) value);
      } else {
        writable.writeLong(value);
      }
    }

    private void writeAttribute(String key, double value, int valueType) {
      writeStreamingString(writable, key);
      writable.writeInt(valueType);
      writable.writeDouble(value);
    }

    private void writeAttribute(String key, boolean value, int valueType) {
      writeStreamingString(writable, key);
      writable.writeInt(valueType);
      writable.writeBoolean(value);
    }

    private void writeAttribute(String key, int value, int valueType) {
      writeStreamingString(writable, key);
      writable.writeInt(valueType);
      // For FLOAT_VALUE_TYPE, write as double for consistency
      if (valueType == FLOAT_VALUE_TYPE) {
        writable.writeDouble((double) value);
      } else {
        writable.writeInt(value);
      }
    }

    private int getFlatMapSize(Map<String, Object> map) {
      int size = 0;
      for (Object value : map.values()) {
        if (value instanceof Map) {
          size += getFlatMapSize((Map<String, Object>) value);
        } else {
          size++;
        }
      }
      return size;
    }

    private void writeFlatMap(String key, Map<String, Object> mapValue) {
      for (Map.Entry<String, Object> entry : mapValue.entrySet()) {
        String newKey = key + '.' + entry.getKey();
        Object newValue = entry.getValue();
        if (newValue instanceof Map) {
          writeFlatMap(newKey, (Map<String, Object>) newValue);
        } else if (newValue instanceof Number) {
          writeAttribute(newKey, ((Number) newValue).doubleValue(), FLOAT_VALUE_TYPE);
        } else if (newValue instanceof Boolean) {
          writeAttribute(newKey, (Boolean) newValue, BOOL_VALUE_TYPE);
        } else {
          writeAttribute(newKey, String.valueOf(newValue), STRING_VALUE_TYPE);
        }
      }
    }
  }

  /** Payload implementation for V1.0 format. */
  private static class PayloadV1_0 extends Payload {
    private final StringTable stringTable;

    private PayloadV1_0(StringTable stringTable) {
      this.stringTable = stringTable;
    }

    @Override
    public int sizeInBytes() {
      // Map header + array header + body
      return msgpackMapHeaderSize(1) + msgpackArrayHeaderSize(traceCount()) + body.remaining();
    }

    @Override
    public void writeTo(WritableByteChannel channel) throws IOException {
      // Write the tracer payload map with chunks field (field 11)
      // For simplicity, we write just the chunks array wrapped in a minimal payload structure
      ByteBuffer mapHeader = msgpackMapHeader(1);
      while (mapHeader.hasRemaining()) {
        channel.write(mapHeader);
      }

      // Write field ID 11 (chunks)
      ByteBuffer fieldId = ByteBuffer.allocate(1).put(0, (byte) FIELD_CHUNKS);
      while (fieldId.hasRemaining()) {
        channel.write(fieldId);
      }

      // Write array header for trace count
      ByteBuffer header = msgpackArrayHeader(traceCount());
      while (header.hasRemaining()) {
        channel.write(header);
      }

      // Write body
      while (body.hasRemaining()) {
        channel.write(body);
      }
    }

    @Override
    public RequestBody toRequest() {
      return msgpackRequestBodyOf(
          Arrays.asList(
              msgpackMapHeader(1),
              ByteBuffer.allocate(1).put(0, (byte) FIELD_CHUNKS),
              msgpackArrayHeader(traceCount()),
              body));
    }
  }
}

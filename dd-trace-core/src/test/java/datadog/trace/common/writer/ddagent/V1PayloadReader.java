package datadog.trace.common.writer.ddagent;

import static datadog.trace.common.writer.ddagent.TraceMapperV1.VALUE_TYPE_ARRAY;
import static datadog.trace.common.writer.ddagent.TraceMapperV1.VALUE_TYPE_BOOLEAN;
import static datadog.trace.common.writer.ddagent.TraceMapperV1.VALUE_TYPE_BYTES;
import static datadog.trace.common.writer.ddagent.TraceMapperV1.VALUE_TYPE_FLOAT;
import static datadog.trace.common.writer.ddagent.TraceMapperV1.VALUE_TYPE_INT;
import static datadog.trace.common.writer.ddagent.TraceMapperV1.VALUE_TYPE_STRING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.msgpack.core.MessageFormat;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;
import org.msgpack.core.buffer.ArrayBufferInput;
import org.msgpack.value.ValueType;

/**
 * Shared decoder for the msgpack V1 trace payload wire format produced by {@link TraceMapperV1}.
 *
 * <p>This is the single source of truth for the low-level parse primitives used by the V1 payload
 * tests (both {@code TraceMapperV1PayloadTest} and {@code DDSpanSerializationTest}). Methods take
 * an explicit {@link MessageUnpacker} and {@code stringTable} so callers that need to assert on
 * wire-level details can interleave their own reads while keeping the streaming-string table in
 * sync. The string table must be seeded with the empty string at index 0, mirroring {@code
 * TraceMapperV1}.
 */
public final class V1PayloadReader {

  /** msgpack field ids of the top-level payload (header) map, mirroring {@code buildHeader}. */
  private static final class PayloadField {
    static final int CONTAINER_ID = 2;
    static final int LANGUAGE_NAME = 3;
    static final int LANGUAGE_VERSION = 4;
    static final int TRACER_VERSION = 5;
    static final int RUNTIME_ID = 6;
    static final int ENV = 7;
    static final int HOSTNAME = 8;
    static final int APP_VERSION = 9;
    static final int ATTRIBUTES = 10;
    static final int CHUNKS = 11;

    private PayloadField() {}
  }

  /** msgpack field ids of a trace chunk map, mirroring {@code TraceMapperV1.map} (no field 5). */
  private static final class ChunkField {
    static final int PRIORITY = 1;
    static final int ORIGIN = 2;
    static final int ATTRIBUTES = 3;
    static final int SPANS = 4;
    static final int TRACE_ID = 6;
    static final int SAMPLING_MECHANISM = 7;

    private ChunkField() {}
  }

  /** msgpack field ids of a span map, mirroring {@code encodeSpans} (16 fields). */
  private static final class SpanField {
    static final int SERVICE = 1;
    static final int NAME = 2;
    static final int RESOURCE = 3;
    static final int SPAN_ID = 4;
    static final int PARENT_ID = 5;
    static final int START = 6;
    static final int DURATION = 7;
    static final int ERROR = 8;
    static final int ATTRIBUTES = 9;
    static final int TYPE = 10;
    static final int LINKS = 11;
    static final int EVENTS = 12;
    static final int ENV = 13;
    static final int VERSION = 14;
    static final int COMPONENT = 15;
    static final int KIND = 16;

    private SpanField() {}
  }

  /** msgpack field ids of a span link map, mirroring {@code encodeSpanLinks} (5 fields). */
  private static final class LinkField {
    static final int TRACE_ID = 1;
    static final int SPAN_ID = 2;
    static final int ATTRIBUTES = 3;
    static final int TRACE_STATE = 4;
    static final int TRACE_FLAGS = 5;

    private LinkField() {}
  }

  /** msgpack field ids of a span event map, mirroring {@code encodeSpanEvents} (3 fields). */
  private static final class EventField {
    static final int TIME_UNIX_NANO = 1;
    static final int NAME = 2;
    static final int ATTRIBUTES = 3;

    private EventField() {}
  }

  private V1PayloadReader() {}

  /** Decodes the first span of the first chunk of an encoded V1 payload. */
  public static V1Span readFirstSpan(byte[] encoded) throws IOException {
    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(new ArrayBufferInput(encoded));
    List<String> stringTable = newStringTable();
    return readFirstSpan(unpacker, stringTable);
  }

  /** Creates a string table seeded with the empty string at index 0, as the writer expects. */
  public static List<String> newStringTable() {
    List<String> stringTable = new ArrayList<>();
    stringTable.add("");
    return stringTable;
  }

  public static V1Span readFirstSpan(MessageUnpacker unpacker, List<String> stringTable)
      throws IOException {
    int payloadFieldCount = unpacker.unpackMapHeader();
    for (int i = 0; i < payloadFieldCount; i++) {
      int payloadFieldId = unpacker.unpackInt();
      if (payloadFieldId != PayloadField.CHUNKS) {
        skipPayloadField(unpacker, payloadFieldId, stringTable);
        continue;
      }
      int chunkCount = unpacker.unpackArrayHeader();
      assertEquals(1, chunkCount);
      int chunkFieldCount = unpacker.unpackMapHeader();
      for (int j = 0; j < chunkFieldCount; j++) {
        int chunkFieldId = unpacker.unpackInt();
        if (chunkFieldId != ChunkField.SPANS) {
          skipChunkField(unpacker, chunkFieldId, stringTable);
          continue;
        }
        int spanCount = unpacker.unpackArrayHeader();
        assertEquals(1, spanCount);
        return decodeSpan(unpacker, stringTable);
      }
    }
    throw new AssertionError("Could not find first span in v1 payload");
  }

  private static V1Span decodeSpan(MessageUnpacker unpacker, List<String> stringTable)
      throws IOException {
    int spanFieldCount = unpacker.unpackMapHeader();
    Map<String, Object> attributes = Collections.emptyMap();
    List<V1SpanLink> links = Collections.emptyList();
    List<V1SpanEvent> events = Collections.emptyList();
    for (int i = 0; i < spanFieldCount; i++) {
      int spanFieldId = unpacker.unpackInt();
      switch (spanFieldId) {
        case SpanField.ATTRIBUTES:
          attributes = readAttributes(unpacker, stringTable);
          break;
        case SpanField.LINKS:
          links = readSpanLinks(unpacker, stringTable);
          break;
        case SpanField.EVENTS:
          events = readSpanEvents(unpacker, stringTable);
          break;
        default:
          skipSpanField(unpacker, spanFieldId, stringTable);
      }
    }
    return new V1Span(attributes, links, events);
  }

  public static Map<String, Object> readAttributes(
      MessageUnpacker unpacker, List<String> stringTable) throws IOException {
    int arraySize = unpacker.unpackArrayHeader();
    assertEquals(0, arraySize % 3);
    int attributeCount = arraySize / 3;
    Map<String, Object> attributes = new HashMap<>();
    for (int i = 0; i < attributeCount; i++) {
      String key = readStreamingString(unpacker, stringTable);
      int valueType = unpacker.unpackInt();
      attributes.put(key, readAttributeValue(unpacker, stringTable, valueType));
    }
    return attributes;
  }

  private static Object readAttributeValue(
      MessageUnpacker unpacker, List<String> stringTable, int valueType) throws IOException {
    switch (valueType) {
      case VALUE_TYPE_STRING:
        return readStreamingString(unpacker, stringTable);
      case VALUE_TYPE_BOOLEAN:
        return unpacker.unpackBoolean();
      case VALUE_TYPE_FLOAT:
        return unpacker.unpackDouble();
      case VALUE_TYPE_BYTES:
        return readBinary(unpacker);
      default:
        throw new IllegalArgumentException("Unknown v1 attribute value type: " + valueType);
    }
  }

  public static List<V1SpanLink> readSpanLinks(MessageUnpacker unpacker, List<String> stringTable)
      throws IOException {
    int linkCount = unpacker.unpackArrayHeader();
    List<V1SpanLink> links = new ArrayList<>(linkCount);
    for (int i = 0; i < linkCount; i++) {
      int linkFieldCount = unpacker.unpackMapHeader();
      byte[] traceId = null;
      long spanId = 0;
      Map<String, Object> attributes = Collections.emptyMap();
      String traceState = "";
      long traceFlags = 0;
      for (int j = 0; j < linkFieldCount; j++) {
        int linkFieldId = unpacker.unpackInt();
        switch (linkFieldId) {
          case LinkField.TRACE_ID:
            traceId = readBinary(unpacker);
            break;
          case LinkField.SPAN_ID:
            spanId = unpackUnsignedLong(unpacker);
            break;
          case LinkField.ATTRIBUTES:
            attributes = readAttributes(unpacker, stringTable);
            break;
          case LinkField.TRACE_STATE:
            traceState = readStreamingString(unpacker, stringTable);
            break;
          case LinkField.TRACE_FLAGS:
            traceFlags = unpackUnsignedLong(unpacker);
            break;
          default:
            throw new IllegalArgumentException("Unexpected v1 span link field id: " + linkFieldId);
        }
      }
      links.add(new V1SpanLink(traceId, spanId, attributes, traceState, traceFlags));
    }
    return links;
  }

  public static List<V1SpanEvent> readSpanEvents(MessageUnpacker unpacker, List<String> stringTable)
      throws IOException {
    int eventCount = unpacker.unpackArrayHeader();
    List<V1SpanEvent> events = new ArrayList<>(eventCount);
    for (int i = 0; i < eventCount; i++) {
      int eventFieldCount = unpacker.unpackMapHeader();
      long timeUnixNano = 0;
      String name = null;
      Map<String, Object> attributes = Collections.emptyMap();
      for (int j = 0; j < eventFieldCount; j++) {
        int eventFieldId = unpacker.unpackInt();
        switch (eventFieldId) {
          case EventField.TIME_UNIX_NANO:
            timeUnixNano = unpacker.unpackLong();
            break;
          case EventField.NAME:
            name = readStreamingString(unpacker, stringTable);
            break;
          case EventField.ATTRIBUTES:
            attributes = readEventAttributes(unpacker, stringTable);
            break;
          default:
            throw new IllegalArgumentException(
                "Unexpected v1 span event field id: " + eventFieldId);
        }
      }
      events.add(new V1SpanEvent(timeUnixNano, name, attributes));
    }
    return events;
  }

  private static Map<String, Object> readEventAttributes(
      MessageUnpacker unpacker, List<String> stringTable) throws IOException {
    int arraySize = unpacker.unpackArrayHeader();
    assertEquals(0, arraySize % 3);
    int attributeCount = arraySize / 3;
    Map<String, Object> attributes = new HashMap<>();
    for (int i = 0; i < attributeCount; i++) {
      String key = readStreamingString(unpacker, stringTable);
      int valueType = unpacker.unpackInt();
      Object value;
      switch (valueType) {
        case VALUE_TYPE_STRING:
          value = readStreamingString(unpacker, stringTable);
          break;
        case VALUE_TYPE_BOOLEAN:
          value = unpacker.unpackBoolean();
          break;
        case VALUE_TYPE_FLOAT:
          value = unpacker.unpackDouble();
          break;
        case VALUE_TYPE_INT:
          value = unpacker.unpackLong();
          break;
        case VALUE_TYPE_ARRAY:
          value = readEventArrayValue(unpacker, stringTable);
          break;
        default:
          throw new IllegalArgumentException("Unknown v1 event attribute value type: " + valueType);
      }
      attributes.put(key, value);
    }
    return attributes;
  }

  private static List<Object> readEventArrayValue(
      MessageUnpacker unpacker, List<String> stringTable) throws IOException {
    int arraySize = unpacker.unpackArrayHeader();
    assertEquals(0, arraySize % 2);
    int itemCount = arraySize / 2;
    List<Object> values = new ArrayList<>(itemCount);
    for (int i = 0; i < itemCount; i++) {
      int itemType = unpacker.unpackInt();
      switch (itemType) {
        case VALUE_TYPE_STRING:
          values.add(readStreamingString(unpacker, stringTable));
          break;
        case VALUE_TYPE_BOOLEAN:
          values.add(unpacker.unpackBoolean());
          break;
        case VALUE_TYPE_FLOAT:
          values.add(unpacker.unpackDouble());
          break;
        case VALUE_TYPE_INT:
          values.add(unpacker.unpackLong());
          break;
        default:
          throw new IllegalArgumentException("Unknown v1 event array item type: " + itemType);
      }
    }
    return values;
  }

  public static void skipPayloadField(
      MessageUnpacker unpacker, int fieldId, List<String> stringTable) throws IOException {
    switch (fieldId) {
      case PayloadField.CONTAINER_ID:
      case PayloadField.LANGUAGE_NAME:
      case PayloadField.LANGUAGE_VERSION:
      case PayloadField.TRACER_VERSION:
      case PayloadField.RUNTIME_ID:
      case PayloadField.ENV:
      case PayloadField.HOSTNAME:
      case PayloadField.APP_VERSION:
        readStreamingString(unpacker, stringTable);
        break;
      case PayloadField.ATTRIBUTES:
        readAttributes(unpacker, stringTable);
        break;
      default:
        throw new IllegalArgumentException("Unexpected v1 payload field id: " + fieldId);
    }
  }

  public static void skipChunkField(MessageUnpacker unpacker, int fieldId, List<String> stringTable)
      throws IOException {
    switch (fieldId) {
      case ChunkField.PRIORITY:
      case ChunkField.SAMPLING_MECHANISM:
        unpacker.unpackInt();
        break;
      case ChunkField.ORIGIN:
        readStreamingString(unpacker, stringTable);
        break;
      case ChunkField.ATTRIBUTES:
        readAttributes(unpacker, stringTable);
        break;
      case ChunkField.SPANS:
        int spanCount = unpacker.unpackArrayHeader();
        for (int i = 0; i < spanCount; i++) {
          decodeSpan(unpacker, stringTable);
        }
        break;
      case ChunkField.TRACE_ID:
        readBinary(unpacker);
        break;
      default:
        throw new IllegalArgumentException("Unexpected v1 chunk field id: " + fieldId);
    }
  }

  public static void skipSpanField(MessageUnpacker unpacker, int fieldId, List<String> stringTable)
      throws IOException {
    switch (fieldId) {
      case SpanField.SERVICE:
      case SpanField.NAME:
      case SpanField.RESOURCE:
      case SpanField.TYPE:
      case SpanField.ENV:
      case SpanField.VERSION:
      case SpanField.COMPONENT:
        readStreamingString(unpacker, stringTable);
        break;
      case SpanField.SPAN_ID:
      case SpanField.PARENT_ID:
        unpackUnsignedLong(unpacker);
        break;
      case SpanField.START:
      case SpanField.DURATION:
        unpacker.unpackLong();
        break;
      case SpanField.ERROR:
        unpacker.unpackBoolean();
        break;
      case SpanField.ATTRIBUTES:
        readAttributes(unpacker, stringTable);
        break;
      case SpanField.LINKS:
        readSpanLinks(unpacker, stringTable);
        break;
      case SpanField.EVENTS:
        readSpanEvents(unpacker, stringTable);
        break;
      case SpanField.KIND:
        unpacker.unpackInt();
        break;
      default:
        throw new IllegalArgumentException("Unexpected v1 span field id: " + fieldId);
    }
  }

  /**
   * Reads a streaming string: either an inline literal (which is appended to the table) or an
   * integer index into the table populated by earlier literals.
   */
  public static String readStreamingString(MessageUnpacker unpacker, List<String> stringTable)
      throws IOException {
    ValueType valueType = unpacker.getNextFormat().getValueType();
    if (valueType == ValueType.STRING) {
      String value = unpacker.unpackString();
      stringTable.add(value);
      return value;
    }
    if (valueType == ValueType.INTEGER) {
      int index = unpacker.unpackInt();
      assertTrue(index >= 0 && index < stringTable.size(), "Invalid string-table index: " + index);
      return stringTable.get(index);
    }
    throw new IllegalArgumentException("Expected v1 streaming string, got: " + valueType);
  }

  public static long unpackUnsignedLong(MessageUnpacker unpacker) throws IOException {
    if (unpacker.getNextFormat() == MessageFormat.UINT64) {
      return DDSpanId.from(unpacker.unpackBigInteger().toString());
    }
    return unpacker.unpackLong();
  }

  public static byte[] readBinary(MessageUnpacker unpacker) throws IOException {
    byte[] bytes = new byte[unpacker.unpackBinaryHeader()];
    unpacker.readPayload(bytes);
    return bytes;
  }

  /** Encodes a trace id the same way {@link TraceMapperV1} serializes it (16 big-endian bytes). */
  public static byte[] traceIdBytes(DDTraceId traceId) {
    return ByteBuffer.allocate(16)
        .putLong(traceId.toHighOrderLong())
        .putLong(traceId.toLong())
        .array();
  }

  /** A decoded V1 span, exposing only the fields the tests assert on. */
  public static final class V1Span {
    private final Map<String, Object> attributes;
    private final List<V1SpanLink> links;
    private final List<V1SpanEvent> events;

    private V1Span(
        Map<String, Object> attributes, List<V1SpanLink> links, List<V1SpanEvent> events) {
      this.attributes = attributes;
      this.links = links;
      this.events = events;
    }

    public Map<String, Object> getAttributes() {
      return attributes;
    }

    public List<V1SpanLink> getLinks() {
      return links;
    }

    public List<V1SpanEvent> getEvents() {
      return events;
    }
  }

  /** A decoded V1 structured span link. */
  public static final class V1SpanLink {
    private final byte[] traceId;
    private final long spanId;
    private final Map<String, Object> attributes;
    private final String traceState;
    private final long traceFlags;

    private V1SpanLink(
        byte[] traceId,
        long spanId,
        Map<String, Object> attributes,
        String traceState,
        long traceFlags) {
      this.traceId = traceId;
      this.spanId = spanId;
      this.attributes = attributes;
      this.traceState = traceState;
      this.traceFlags = traceFlags;
    }

    public byte[] getTraceId() {
      return traceId;
    }

    public long getSpanId() {
      return spanId;
    }

    public Map<String, Object> getAttributes() {
      return attributes;
    }

    public String getTraceState() {
      return traceState;
    }

    public long getTraceFlags() {
      return traceFlags;
    }
  }

  /** A decoded V1 structured span event. */
  public static final class V1SpanEvent {
    private final long timeUnixNano;
    private final String name;
    private final Map<String, Object> attributes;

    private V1SpanEvent(long timeUnixNano, String name, Map<String, Object> attributes) {
      this.timeUnixNano = timeUnixNano;
      this.name = name;
      this.attributes = attributes;
    }

    public long getTimeUnixNano() {
      return timeUnixNano;
    }

    public String getName() {
      return name;
    }

    public Map<String, Object> getAttributes() {
      return attributes;
    }
  }
}

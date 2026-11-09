package datadog.trace.test.agent.decoder.v1.raw;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import datadog.trace.test.agent.decoder.DecodedSpanEvent;
import datadog.trace.test.agent.decoder.DecodedSpanLink;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.msgpack.core.MessageBufferPacker;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;

/**
 * Tests the {@code v1.0} span decoder against identifiers whose high bit is set. {@code
 * TraceMapperV1} writes span identifiers with {@code writeUnsignedLong}, so those arrive as msgpack
 * {@code UINT64} — a shape the {@code sample_v1.msgpack} fixture never produces.
 */
class SpanV1Test {
  // 0xfedcba9876543210: an unsigned 64-bit identifier above Long.MAX_VALUE.
  private static final String UNSIGNED_ID = "18364758544493064720";
  private static final long UNSIGNED_ID_BITS = 0xfedcba9876543210L;

  @Test
  void decodesUnsignedSpanAndParentIds() throws IOException {
    MessageBufferPacker packer = MessagePack.newDefaultBufferPacker();
    packer.packMapHeader(2);
    packer.packInt(SpanV1.SPAN_FIELD_SPAN_ID);
    packer.packBigInteger(new BigInteger(UNSIGNED_ID));
    packer.packInt(SpanV1.SPAN_FIELD_PARENT_ID);
    packer.packBigInteger(new BigInteger(UNSIGNED_ID));

    SpanV1 span = SpanV1.unpack(unpacker(packer), new ArrayList<>());

    assertEquals(UNSIGNED_ID_BITS, span.getSpanId(), "span id above Long.MAX_VALUE");
    assertEquals(UNSIGNED_ID_BITS, span.getParentId(), "parent id above Long.MAX_VALUE");
  }

  @Test
  void decodesUnsignedSignedAndZeroSpanIdsAlike() throws IOException {
    assertEquals(UNSIGNED_ID_BITS, spanIdOf(new BigInteger(UNSIGNED_ID)));
    assertEquals(42L, spanIdOf(BigInteger.valueOf(42)));
    assertEquals(0L, spanIdOf(BigInteger.ZERO));
  }

  @Test
  void decodesUnsignedLinkSpanId() throws IOException {
    MessageBufferPacker packer = MessagePack.newDefaultBufferPacker();
    packer.packArrayHeader(1);
    packer.packMapHeader(2);
    packer.packInt(SpanV1.LINK_FIELD_TRACE_ID);
    packer.packBinaryHeader(16);
    packer.writePayload(traceIdBytes());
    packer.packInt(SpanV1.LINK_FIELD_SPAN_ID);
    packer.packBigInteger(new BigInteger(UNSIGNED_ID));

    List<DecodedSpanLink> links = SpanV1.unpackSpanLinks(unpacker(packer), new ArrayList<>());

    assertEquals(1, links.size());
    assertEquals(UNSIGNED_ID_BITS, links.get(0).getSpanId(), "link span id above Long.MAX_VALUE");
    assertEquals(UNSIGNED_ID_BITS, links.get(0).getTraceId(), "link trace id low-order half");
  }

  @Test
  void noSpanLinksDecodeToAnEmptyList() throws IOException {
    MessageBufferPacker packer = MessagePack.newDefaultBufferPacker();
    packer.packArrayHeader(0);

    assertEquals(emptyList(), SpanV1.unpackSpanLinks(unpacker(packer), new ArrayList<>()));
  }

  @Test
  void decodesSpanEventsWithTypedAttributes() throws IOException {
    MessageBufferPacker packer = MessagePack.newDefaultBufferPacker();
    packer.packArrayHeader(1);
    packer.packMapHeader(3);
    packer.packInt(SpanV1.EVENT_FIELD_TIME);
    packer.packLong(1_234L);
    packer.packInt(SpanV1.EVENT_FIELD_NAME);
    packer.packString("event");
    packer.packInt(SpanV1.EVENT_FIELD_ATTRIBUTES);
    packer.packArrayHeader(5 * 3);
    packAttributeKey(packer, "str", SpanV1.STRING_VALUE_TYPE);
    packer.packString("value");
    packAttributeKey(packer, "bool", SpanV1.BOOL_VALUE_TYPE);
    packer.packBoolean(true);
    packAttributeKey(packer, "int", SpanV1.INT_VALUE_TYPE);
    packer.packLong(42L);
    packAttributeKey(packer, "double", SpanV1.FLOAT_VALUE_TYPE);
    packer.packDouble(12.5d);
    packAttributeKey(packer, "arr", SpanV1.ARRAY_VALUE_TYPE);
    packer.packArrayHeader(2 * 2);
    packer.packInt(SpanV1.STRING_VALUE_TYPE);
    packer.packString("x");
    packer.packInt(SpanV1.INT_VALUE_TYPE);
    packer.packLong(7L);

    List<DecodedSpanEvent> events = SpanV1.unpackSpanEvents(unpacker(packer), new ArrayList<>());

    assertEquals(1, events.size());
    DecodedSpanEvent event = events.get(0);
    assertEquals("event", event.getName());
    assertEquals(1_234L, event.getTimeUnixNano());
    Map<String, Object> expected = new LinkedHashMap<>();
    expected.put("str", "value");
    expected.put("bool", true);
    expected.put("int", 42L);
    expected.put("double", 12.5d);
    expected.put("arr", asList("x", 7L));
    assertEquals(expected, event.getAttributes());
  }

  @Test
  void spanEventsKeepTheStringTableInSync() throws IOException {
    // The event introduces "event" and "key" in the string table, then the span type refers to
    // "key" by its index: decoding the events must have registered both strings.
    MessageBufferPacker packer = MessagePack.newDefaultBufferPacker();
    packer.packMapHeader(2);
    packer.packInt(SpanV1.SPAN_FIELD_SPAN_EVENTS);
    packer.packArrayHeader(1);
    packer.packMapHeader(3);
    packer.packInt(SpanV1.EVENT_FIELD_TIME);
    packer.packLong(1L);
    packer.packInt(SpanV1.EVENT_FIELD_NAME);
    packer.packString("event");
    packer.packInt(SpanV1.EVENT_FIELD_ATTRIBUTES);
    packer.packArrayHeader(3);
    packAttributeKey(packer, "key", SpanV1.INT_VALUE_TYPE);
    packer.packLong(1L);
    packer.packInt(SpanV1.SPAN_FIELD_TYPE);
    packer.packInt(2);
    List<String> stringTable = new ArrayList<>();
    stringTable.add("");

    SpanV1 span = SpanV1.unpack(unpacker(packer), stringTable);

    assertEquals("key", span.getType(), "string index after the events");
    assertEquals(1, span.getEvents().size());
    assertEquals("event", span.getEvents().get(0).getName());
  }

  @Test
  void rejectsSpanEventsMissingRequiredFields() throws IOException {
    MessageBufferPacker packer = MessagePack.newDefaultBufferPacker();
    packer.packArrayHeader(1);
    packer.packMapHeader(1);
    packer.packInt(SpanV1.EVENT_FIELD_NAME);
    packer.packString("event");
    MessageUnpacker unpacker = unpacker(packer);

    assertThrows(
        IllegalArgumentException.class,
        () -> SpanV1.unpackSpanEvents(unpacker, new ArrayList<>()),
        "missing time");
  }

  @Test
  void noSpanEventsDecodeToAnEmptyList() throws IOException {
    MessageBufferPacker packer = MessagePack.newDefaultBufferPacker();
    packer.packArrayHeader(0);

    assertEquals(emptyList(), SpanV1.unpackSpanEvents(unpacker(packer), new ArrayList<>()));
  }

  private static void packAttributeKey(MessageBufferPacker packer, String key, int valueType)
      throws IOException {
    packer.packString(key);
    packer.packInt(valueType);
  }

  private static long spanIdOf(BigInteger id) throws IOException {
    MessageBufferPacker packer = MessagePack.newDefaultBufferPacker();
    packer.packMapHeader(1);
    packer.packInt(SpanV1.SPAN_FIELD_SPAN_ID);
    packer.packBigInteger(id);
    return SpanV1.unpack(unpacker(packer), new ArrayList<>()).getSpanId();
  }

  /** A 16-byte trace identifier whose low-order half is {@link #UNSIGNED_ID_BITS}. */
  private static byte[] traceIdBytes() {
    byte[] bytes = new byte[16];
    for (int i = 0; i < 8; i++) {
      bytes[8 + i] = (byte) (UNSIGNED_ID_BITS >>> (8 * (7 - i)));
    }
    return bytes;
  }

  private static MessageUnpacker unpacker(MessageBufferPacker packer) throws IOException {
    packer.close();
    return MessagePack.newDefaultUnpacker(packer.toByteArray());
  }
}

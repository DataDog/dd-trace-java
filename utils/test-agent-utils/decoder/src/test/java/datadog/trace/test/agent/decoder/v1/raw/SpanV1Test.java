package datadog.trace.test.agent.decoder.v1.raw;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import datadog.trace.test.agent.decoder.DecodedSpanLink;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.msgpack.core.MessageBufferPacker;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;

/**
 * Tests the {@code v1.0} span decoder against shapes the {@code sample_v1.msgpack} fixture never
 * produces: identifiers whose high bit is set ({@code TraceMapperV1} writes span identifiers with
 * {@code writeUnsignedLong}, so those arrive as msgpack {@code UINT64}) and span events.
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
  void decodesSpanEventsIntoTheEventsTag() throws IOException {
    MessageBufferPacker packer = MessagePack.newDefaultBufferPacker();
    packer.packArrayHeader(2);
    // First event: every attribute value type, introducing new streaming strings.
    packer.packMapHeader(3);
    packer.packInt(SpanV1.EVENT_FIELD_TIME);
    packer.packLong(1791902357478000001L);
    packer.packInt(SpanV1.EVENT_FIELD_NAME);
    packer.packString("configuration-loaded"); // string table index 0
    packer.packInt(SpanV1.EVENT_FIELD_ATTRIBUTES);
    packer.packArrayHeader(5 * 3);
    packer.packString("source").packInt(SpanV1.STRING_VALUE_TYPE).packString("file \"local\"");
    packer.packString("count").packInt(SpanV1.INT_VALUE_TYPE).packLong(3);
    packer.packString("ratio").packInt(SpanV1.FLOAT_VALUE_TYPE).packDouble(2.5);
    packer.packString("cached").packInt(SpanV1.BOOL_VALUE_TYPE).packBoolean(false);
    packer.packString("tags").packInt(SpanV1.ARRAY_VALUE_TYPE).packArrayHeader(2 * 2);
    packer.packInt(SpanV1.STRING_VALUE_TYPE).packInt(0); // reference to "configuration-loaded"
    packer.packInt(SpanV1.INT_VALUE_TYPE).packLong(1);
    // Second event: name by reference, no attributes.
    packer.packMapHeader(3);
    packer.packInt(SpanV1.EVENT_FIELD_TIME);
    packer.packLong(2);
    packer.packInt(SpanV1.EVENT_FIELD_NAME);
    packer.packInt(0);
    packer.packInt(SpanV1.EVENT_FIELD_ATTRIBUTES);
    packer.packArrayHeader(0);
    List<String> stringTable = new ArrayList<>();

    String events = SpanV1.unpackSpanEvents(unpacker(packer), stringTable);

    assertEquals(
        "[{\"time_unix_nano\":1791902357478000001,\"name\":\"configuration-loaded\","
            + "\"attributes\":{\"source\":\"file \\\"local\\\"\",\"count\":3,\"ratio\":2.5,"
            + "\"cached\":false,\"tags\":[\"configuration-loaded\",1]}},"
            + "{\"time_unix_nano\":2,\"name\":\"configuration-loaded\"}]",
        events);
    assertEquals(
        asList(
            "configuration-loaded", "source", "file \"local\"", "count", "ratio", "cached", "tags"),
        stringTable,
        "event strings registered in the string table");
  }

  @Test
  void spanEventsKeepTheStringTableInSync() throws IOException {
    MessageBufferPacker packer = MessagePack.newDefaultBufferPacker();
    packer.packMapHeader(2);
    packer.packInt(SpanV1.SPAN_FIELD_SPAN_EVENTS);
    packer.packArrayHeader(1);
    packer.packMapHeader(1);
    packer.packInt(SpanV1.EVENT_FIELD_NAME);
    packer.packString("event-name"); // string table index 0
    packer.packInt(SpanV1.SPAN_FIELD_SERVICE);
    packer.packInt(0); // reference to the string the event introduced

    SpanV1 span = SpanV1.unpack(unpacker(packer), new ArrayList<>());

    assertEquals("[{\"time_unix_nano\":0,\"name\":\"event-name\"}]", span.getMeta().get("events"));
    assertEquals("event-name", span.getService());
  }

  @Test
  void noSpanEventsLeaveNoEventsTag() throws IOException {
    MessageBufferPacker packer = MessagePack.newDefaultBufferPacker();
    packer.packMapHeader(1);
    packer.packInt(SpanV1.SPAN_FIELD_SPAN_EVENTS);
    packer.packArrayHeader(0);

    SpanV1 span = SpanV1.unpack(unpacker(packer), new ArrayList<>());

    assertFalse(span.getMeta().containsKey("events"));
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

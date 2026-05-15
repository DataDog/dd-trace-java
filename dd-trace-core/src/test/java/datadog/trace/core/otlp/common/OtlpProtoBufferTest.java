package datadog.trace.core.otlp.common;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.WireFormat;
import datadog.communication.serialization.GrowableBuffer;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link OtlpProtoBuffer}.
 *
 * <p>The buffer prepends encoded protobuf messages. These messages appear in the final payload in
 * reverse insertion order. Each test verifies the wire encoding via {@link CodedInputStream} where
 * appropriate.
 */
class OtlpProtoBufferTest {

  private OtlpProtoBuffer buffer;

  @BeforeEach
  void setUp() {
    buffer = new OtlpProtoBuffer(16);
  }

  // ─── helpers ─────────────────────────────────────────────────────────────

  /** Reads all available bytes from the buffer without consuming the underlying state. */
  private static byte[] readAll(OtlpProtoBuffer buf) {
    ByteBuffer bb = buf.flip();
    byte[] bytes = new byte[bb.remaining()];
    bb.get(bytes);
    return bytes;
  }

  /** Creates a pre-filled GrowableBuffer containing the given bytes. */
  private static GrowableBuffer growable(byte... body) {
    GrowableBuffer buf = new GrowableBuffer(Math.max(1, body.length));
    if (body.length > 0) {
      buf.put(body);
    }
    return buf;
  }

  // ─── initial state ───────────────────────────────────────────────────────

  @Test
  void initialBufferIsEmpty() {
    assertEquals(0, buffer.flip().remaining());
  }

  @Test
  void initialPayloadIsEmpty() {
    OtlpPayload payload = buffer.toPayload();
    assertEquals(0, payload.getContentLength());
    assertEquals("application/x-protobuf", payload.getContentType());
  }

  // ─── recordMessage(GrowableBuffer, int) ──────────────────────────────────

  @Test
  void recordsSingleByteMessage() throws IOException {
    // tag for field 1 = (1<<3)|2 = 10 → 1 varint byte; length=1 → 1 byte; body=1 → total 3
    int returned = buffer.recordMessage(growable((byte) 0x42), 1);
    assertEquals(3, returned);

    CodedInputStream in = CodedInputStream.newInstance(readAll(buffer));
    int tag = in.readTag();
    assertEquals(1, WireFormat.getTagFieldNumber(tag));
    assertEquals(WireFormat.WIRETYPE_LENGTH_DELIMITED, WireFormat.getTagWireType(tag));
    assertArrayEquals(new byte[] {0x42}, in.readByteArray());
    assertTrue(in.isAtEnd());
  }

  @Test
  void recordsMultiByteBodyMessage() throws IOException {
    byte[] body = {0x01, 0x02, 0x03};
    // tag(field=2)=(2<<3)|2=18 → 1 byte; length=3 → 1 byte; body=3 → total 5
    int returned = buffer.recordMessage(growable(body), 2);
    assertEquals(5, returned);

    CodedInputStream in = CodedInputStream.newInstance(readAll(buffer));
    int tag = in.readTag();
    assertEquals(2, WireFormat.getTagFieldNumber(tag));
    assertArrayEquals(body, in.readByteArray());
    assertTrue(in.isAtEnd());
  }

  @Test
  void recordsEmptyBodyMessage() throws IOException {
    // tag(1 byte) + length-varint(0, 1 byte) + 0 body bytes = 2
    int returned = buffer.recordMessage(new GrowableBuffer(1), 1);
    assertEquals(2, returned);

    CodedInputStream in = CodedInputStream.newInstance(readAll(buffer));
    assertEquals(1, WireFormat.getTagFieldNumber(in.readTag()));
    assertEquals(0, in.readByteArray().length);
    assertTrue(in.isAtEnd());
  }

  @Test
  void recordsMessageWithLargeFieldNumber() throws IOException {
    // field 16: tag=(16<<3)|2=130, needs 2 varint bytes; length=1 → 1 byte; body=1 → total 4
    int returned = buffer.recordMessage(growable((byte) 0xAB), 16);
    assertEquals(4, returned);

    CodedInputStream in = CodedInputStream.newInstance(readAll(buffer));
    int tag = in.readTag();
    assertEquals(16, WireFormat.getTagFieldNumber(tag));
    assertEquals(WireFormat.WIRETYPE_LENGTH_DELIMITED, WireFormat.getTagWireType(tag));
    assertArrayEquals(new byte[] {(byte) 0xAB}, in.readByteArray());
    assertTrue(in.isAtEnd());
  }

  @Test
  void growableBufferIsReusableAfterRecordMessage() throws IOException {
    // Verifies that the finally-block reset allows the same GrowableBuffer to be reused
    GrowableBuffer buf = new GrowableBuffer(16);

    buf.put((byte) 0x01);
    buffer.recordMessage(buf, 1);

    // After reset the buffer should accept new writes immediately
    buf.put((byte) 0x02);
    buffer.recordMessage(buf, 2);

    CodedInputStream in = CodedInputStream.newInstance(readAll(buffer));
    assertEquals(2, WireFormat.getTagFieldNumber(in.readTag()), "field 2 (last) first");
    assertArrayEquals(new byte[] {0x02}, in.readByteArray());
    assertEquals(1, WireFormat.getTagFieldNumber(in.readTag()), "field 1 (first) second");
    assertArrayEquals(new byte[] {0x01}, in.readByteArray());
    assertTrue(in.isAtEnd());
  }

  @Test
  void growableBufferIsReusableAfterRecordMessageWithGrowth() throws IOException {
    // OtlpProtoBuffer growth must not prevent the finally-block reset on the GrowableBuffer
    OtlpProtoBuffer tinyBuf = new OtlpProtoBuffer(1);
    GrowableBuffer buf = new GrowableBuffer(16);

    buf.put(new byte[50]); // forces OtlpProtoBuffer growth
    tinyBuf.recordMessage(buf, 1);

    buf.put((byte) 0x42); // reuse after reset
    tinyBuf.recordMessage(buf, 2);

    CodedInputStream in = CodedInputStream.newInstance(readAll(tinyBuf));
    assertEquals(2, WireFormat.getTagFieldNumber(in.readTag()));
    assertArrayEquals(new byte[] {0x42}, in.readByteArray());
    assertEquals(1, WireFormat.getTagFieldNumber(in.readTag()));
    assertEquals(50, in.readByteArray().length);
    assertTrue(in.isAtEnd());
  }

  // ─── recordMessage(GrowableBuffer, int, int bytesSoFar) ──────────────────

  @Test
  void bytesSoFarIncreasesEncodedLength() {
    // field 1, body 1 byte, bytesSoFar 3 → encoded length = 4
    // numBytes = sizeVarInt(10) + sizeVarInt(4) + 1 = 1+1+1 = 3; return 3+3 = 6
    int returned = buffer.recordMessage(growable((byte) 0x01), 1, 3);
    assertEquals(6, returned);
  }

  @Test
  void bytesSoFarAtVarintBoundaryProducesMultiByteLengthField() {
    // body=1 byte, bytesSoFar=127 → encoded length = 128, which needs 2 varint bytes
    // numBytes = sizeVarInt(10) + sizeVarInt(128) + 1 = 1+2+1 = 4; return 4+127 = 131
    int returned = buffer.recordMessage(growable((byte) 0x01), 1, 127);
    assertEquals(131, returned);
  }

  @Test
  void zeroBytesSoFarMatchesSimpleOverload() {
    OtlpProtoBuffer buf1 = new OtlpProtoBuffer(16);
    OtlpProtoBuffer buf2 = new OtlpProtoBuffer(16);
    int ret1 = buf1.recordMessage(growable((byte) 0x05), 3);
    int ret2 = buf2.recordMessage(growable((byte) 0x05), 3, 0);
    assertEquals(ret1, ret2);
    assertArrayEquals(readAll(buf1), readAll(buf2));
  }

  // ─── recordMessage(byte[]) ────────────────────────────────────────────────

  @Test
  void recordsByteArrayDirectly() {
    // hand-crafted protobuf: field 1 (tag=0x0A), length 3, body [1, 2, 3]
    byte[] encoded = {0x0A, 0x03, 0x01, 0x02, 0x03};
    int returned = buffer.recordMessage(encoded);
    assertEquals(5, returned);
    assertArrayEquals(encoded, readAll(buffer));
  }

  @Test
  void recordsEmptyByteArray() {
    int returned = buffer.recordMessage(new byte[0]);
    assertEquals(0, returned);
    assertEquals(0, buffer.flip().remaining());
  }

  @Test
  void byteArrayMessageAppearsInOutput() throws IOException {
    // Encode field 4, body [0xBE, 0xEF] by hand: tag=(4<<3)|2=34, length=2
    byte[] encoded = {0x22, 0x02, (byte) 0xBE, (byte) 0xEF};
    buffer.recordMessage(encoded);

    CodedInputStream in = CodedInputStream.newInstance(readAll(buffer));
    assertEquals(4, WireFormat.getTagFieldNumber(in.readTag()));
    assertArrayEquals(new byte[] {(byte) 0xBE, (byte) 0xEF}, in.readByteArray());
    assertTrue(in.isAtEnd());
  }

  // ─── message ordering ────────────────────────────────────────────────────

  @Test
  void messagesAppearInReverseInsertionOrder() throws IOException {
    buffer.recordMessage(growable((byte) 0x01), 1); // first inserted
    buffer.recordMessage(growable((byte) 0x02), 2); // second → appears first in output

    CodedInputStream in = CodedInputStream.newInstance(readAll(buffer));
    assertEquals(2, WireFormat.getTagFieldNumber(in.readTag()), "field 2 (last) should be first");
    in.readByteArray();
    assertEquals(1, WireFormat.getTagFieldNumber(in.readTag()), "field 1 (first) should be second");
    in.readByteArray();
    assertTrue(in.isAtEnd());
  }

  @Test
  void threeMessagesAreOrderedInReverseInsertion() throws IOException {
    for (int i = 1; i <= 3; i++) {
      buffer.recordMessage(growable((byte) i), i);
    }

    CodedInputStream in = CodedInputStream.newInstance(readAll(buffer));
    for (int expected = 3; expected >= 1; expected--) {
      assertEquals(expected, WireFormat.getTagFieldNumber(in.readTag()));
      in.readByteArray();
    }
    assertTrue(in.isAtEnd());
  }

  @Test
  void byteArrayAndGrowableMessagesInterleaveInReverseOrder() throws IOException {
    // field 1 hand-encoded: tag=0x0A, length=1, body=0x11
    buffer.recordMessage(new byte[] {0x0A, 0x01, 0x11}); // first
    buffer.recordMessage(growable((byte) 0x22), 2); // second → first in output

    CodedInputStream in = CodedInputStream.newInstance(readAll(buffer));
    assertEquals(2, WireFormat.getTagFieldNumber(in.readTag()), "GrowableBuffer message first");
    in.readByteArray();
    assertEquals(1, WireFormat.getTagFieldNumber(in.readTag()), "byte[] message second");
    in.readByteArray();
    assertTrue(in.isAtEnd());
  }

  // ─── flip() ──────────────────────────────────────────────────────────────

  @Test
  void flipReturnsCorrectByteCount() {
    // tag(1) + length(1) + body(2) = 4 bytes
    buffer.recordMessage(growable((byte) 1, (byte) 2), 1);
    assertEquals(4, buffer.flip().remaining());
  }

  @Test
  void flipIsIdempotent() {
    buffer.recordMessage(growable((byte) 0x55), 1);
    assertArrayEquals(readAll(buffer), readAll(buffer));
  }

  // ─── toPayload() ─────────────────────────────────────────────────────────

  @Test
  void toPayloadHasProtobufContentType() {
    assertEquals("application/x-protobuf", buffer.toPayload().getContentType());
  }

  @Test
  void toPayloadContentLengthMatchesFlipRemaining() {
    buffer.recordMessage(growable((byte) 1, (byte) 2, (byte) 3), 1);
    OtlpPayload payload = buffer.toPayload();
    assertEquals(buffer.flip().remaining(), payload.getContentLength());
  }

  @Test
  void toPayloadContentMatchesFlip() {
    buffer.recordMessage(growable((byte) 0xDE, (byte) 0xAD), 5);

    // Capture expected bytes before toPayload(): both share the same ByteBuffer, so readAll
    // must not consume the position that toPayload() will later restore via flip().
    byte[] expected = readAll(buffer);
    OtlpPayload payload = buffer.toPayload();

    ByteBuffer content = payload.getContent();
    byte[] actual = new byte[content.remaining()];
    content.get(actual);
    assertArrayEquals(expected, actual);
  }

  @Test
  void toPayloadContentIsReadOnly() {
    buffer.recordMessage(growable((byte) 1), 1);
    assertThrows(
        ReadOnlyBufferException.class, () -> buffer.toPayload().getContent().put((byte) 0));
  }

  // ─── reset() ─────────────────────────────────────────────────────────────

  @Test
  void resetClearsBuffer() {
    buffer.recordMessage(growable((byte) 1), 1);
    assertNotEquals(0, buffer.flip().remaining());

    buffer.reset();
    assertEquals(0, buffer.flip().remaining());
  }

  @Test
  void resetAllowsNewMessagesToBeRecorded() throws IOException {
    buffer.recordMessage(growable((byte) 0x01), 1);
    buffer.reset();
    buffer.recordMessage(growable((byte) 0x02), 2);

    CodedInputStream in = CodedInputStream.newInstance(readAll(buffer));
    int tag = in.readTag();
    assertEquals(2, WireFormat.getTagFieldNumber(tag), "only field 2 should be present");
    assertArrayEquals(new byte[] {0x02}, in.readByteArray());
    assertTrue(in.isAtEnd());
  }

  @Test
  void resetOnGrownBufferShrinksToInitialCapacityThenFunctionsCorrectly() {
    // initialCapacity=1; recording 100 bytes forces growth
    OtlpProtoBuffer buf = new OtlpProtoBuffer(1);
    buf.recordMessage(new byte[100]);
    assertEquals(100, buf.flip().remaining());

    buf.reset();
    assertEquals(0, buf.flip().remaining());

    // Should continue to function correctly after shrink
    int size = buf.recordMessage(growable((byte) 0x42), 1);
    assertEquals(3, size);
    assertEquals(3, buf.flip().remaining());
  }

  @Test
  void resetPreservesPayloadContentLength() {
    buffer.recordMessage(growable((byte) 0xAB), 1);
    OtlpPayload payload = buffer.toPayload();
    int expectedLength = payload.getContentLength();
    assertTrue(expectedLength > 0);

    buffer.reset();

    // contentLength is eagerly captured in the payload constructor and unaffected by reset
    assertEquals(expectedLength, payload.getContentLength());
  }

  @Test
  void payloadContentRemainsReadableAfterReset() {
    buffer.recordMessage(growable((byte) 0xAB, (byte) 0xCD), 7);
    byte[] expected = readAll(buffer);
    OtlpPayload payload = buffer.toPayload();

    buffer.reset();

    ByteBuffer content = payload.getContent();
    byte[] actual = new byte[content.remaining()];
    content.get(actual);
    assertArrayEquals(expected, actual, "reset() must not corrupt existing payload content");
  }

  @Test
  void payloadContentRemainsReadableAfterResetOnGrownBuffer() {
    // initialCapacity=4; recording 50 bytes forces buffer growth, so reset() allocates a new
    // ByteBuffer — the payload must still reference the old (orphaned) one correctly.
    OtlpProtoBuffer buf = new OtlpProtoBuffer(4);
    buf.recordMessage(new byte[50]);
    byte[] expected = readAll(buf);
    OtlpPayload payload = buf.toPayload();

    buf.reset();

    ByteBuffer content = payload.getContent();
    byte[] actual = new byte[content.remaining()];
    content.get(actual);
    assertArrayEquals(
        expected, actual, "reset() on a grown buffer must not corrupt existing payload content");
  }

  // ─── buffer growth ────────────────────────────────────────────────────────

  @Test
  void bufferAccommodatesMessageLargerThanInitialCapacity() {
    OtlpProtoBuffer buf = new OtlpProtoBuffer(4);
    byte[] big = new byte[50];
    int returned = buf.recordMessage(big);
    assertEquals(50, returned);
    assertArrayEquals(big, readAll(buf));
  }

  @Test
  void growthPreservesAlreadyRecordedMessages() throws IOException {
    // initialCapacity=4; first message fills it exactly, second triggers growth
    OtlpProtoBuffer buf = new OtlpProtoBuffer(4);
    buf.recordMessage(growable((byte) 0x01, (byte) 0x02), 1); // tag+len+2body = 4 bytes
    buf.recordMessage(growable((byte) 0x03, (byte) 0x04), 2); // 4 more bytes → forces growth

    byte[] bytes = readAll(buf);
    assertEquals(8, bytes.length);

    CodedInputStream in = CodedInputStream.newInstance(bytes);
    assertEquals(2, WireFormat.getTagFieldNumber(in.readTag()));
    assertArrayEquals(new byte[] {0x03, 0x04}, in.readByteArray());
    assertEquals(1, WireFormat.getTagFieldNumber(in.readTag()));
    assertArrayEquals(new byte[] {0x01, 0x02}, in.readByteArray());
    assertTrue(in.isAtEnd());
  }

  @Test
  void repeatedGrowthsPreserveAllMessages() throws IOException {
    // initialCapacity=4; each 4-byte message exhausts remaining, forcing growth each round
    OtlpProtoBuffer buf = new OtlpProtoBuffer(4);
    for (int i = 1; i <= 4; i++) {
      buf.recordMessage(growable((byte) i, (byte) (i + 10)), i);
    }

    CodedInputStream in = CodedInputStream.newInstance(readAll(buf));
    for (int expected = 4; expected >= 1; expected--) {
      assertEquals(expected, WireFormat.getTagFieldNumber(in.readTag()));
      assertArrayEquals(new byte[] {(byte) expected, (byte) (expected + 10)}, in.readByteArray());
    }
    assertTrue(in.isAtEnd());
  }
}

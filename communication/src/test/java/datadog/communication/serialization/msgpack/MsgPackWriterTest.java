package datadog.communication.serialization.msgpack;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.communication.serialization.ByteBufferConsumer;
import datadog.communication.serialization.Codec;
import datadog.communication.serialization.FlushingBuffer;
import datadog.communication.serialization.Mapper;
import datadog.communication.serialization.MessageFormatter;
import datadog.communication.serialization.StreamingBuffer;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;

public class MsgPackWriterTest {

  @Test
  public void testOverflow() {
    MessageFormatter packer = new MsgPackWriter(newBuffer(25, (messageCount, buffer) -> {}));
    assertFalse(
        packer.format(
            new HashMap<String, String>() {
              {
                put("foo", "abcdefghijklmnopqrstuvwxyz");
              }
            },
            (Mapper<Map<String, String>>) (data, writable) -> writable.writeObject(data, null)));
  }

  @Test
  public void testInsertAfterOverflow() {
    Mapper<String> mapper = (data, writable) -> writable.writeString(data, null);
    MessageFormatter packer =
        new MsgPackWriter(
            newBuffer(
                2 + 25, // enough space for a 25 element string and its 2 byte header
                (messageCount, buffer) -> {}));
    assertTrue(packer.format("abcdefghijklmnopqrstuvwxy", mapper), "data fits in buffer");
    assertFalse(
        packer.format("abcdefghijklmnopqrstuvwxyz", mapper), "data doesn't fit in finite buffer");
    assertTrue(
        packer.format("abcdefghijklmnopqrstuvwxy", mapper), "data fits in buffer after overflow");
  }

  @Test
  public void testFlushOfOverflow() {
    final List<String> flushed = new ArrayList<>();
    Mapper<String> mapper = (data, writable) -> writable.writeString(data, null);
    MessageFormatter packer =
        new MsgPackWriter(
            newBuffer(
                2 + 25, // enough space for a 25 element string and its 2 byte header
                (messageCount, buffer) -> {
                  MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffer);
                  for (int i = 0; i < messageCount; ++i) {
                    try {
                      flushed.add(unpacker.unpackString());
                    } catch (Exception error) {
                      Assertions.fail(error.getMessage());
                    }
                  }
                }));
    assertTrue(packer.format("abcdefghijklm", mapper), "data fits in buffer");
    assertTrue(
        packer.format("nopqrstuvwxyz", mapper), "data fits in empty buffer but triggers flush");
    assertTrue(
        packer.format("abcdefghijklmnopqrstuvwxy", mapper), "data fits in buffer after overflow");
    assertEquals(2, flushed.size());
    assertEquals("abcdefghijklm", flushed.get(0));
    assertEquals("nopqrstuvwxyz", flushed.get(1));
  }

  @Test
  public void testRecycle() {
    final AtomicInteger i = new AtomicInteger();
    Map<String, String> map =
        new HashMap<String, String>() {
          {
            put("foo", "abcd");
          }
        };
    MsgPackWriter packer =
        new MsgPackWriter(newBuffer(25, (messageCount, buffer) -> i.getAndIncrement()));
    Mapper<Object> mapper = (data, writable) -> writable.writeObject(data, null);
    packer.format(map, mapper);
    packer.format(map, mapper);
    packer.format(map, mapper);
    packer.format(map, mapper);
    assertEquals(1, i.getAndIncrement());
  }

  @Test
  public void testWriteBinary() {
    final byte[] data = new byte[] {1, 2, 3, 4};
    MessageFormatter messageFormatter =
        new MsgPackWriter(
            newBuffer(
                25,
                (messageCount, buffy) -> {
                  MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffy);
                  try {
                    int length = unpacker.unpackBinaryHeader();
                    assertEquals(4, length);
                    assertArrayEquals(data, unpacker.readPayload(length));
                  } catch (IOException e) {
                    Assertions.fail(e.getMessage());
                  }
                }));
    messageFormatter.format(
        data, (data1, writable) -> writable.writeBinary(data1, 0, data1.length));
    messageFormatter.flush();
  }

  @Test
  public void testWriteBinaryNoArgVariant() {
    MsgPackWriter writer =
        new MsgPackWriter(
            newBuffer(
                10,
                (messageCount, buffer) -> {
                  MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffer);
                  try {
                    assertEquals(unpacker.unpackBinaryHeader(), 6);
                    assertArrayEquals(
                        unpacker.readPayload(6), "foobar".getBytes(StandardCharsets.UTF_8));
                  } catch (IOException e) {
                    Assertions.fail(e.getMessage());
                  }
                }));
    writer.writeBinary("foobar".getBytes(StandardCharsets.UTF_8));
  }

  @Test
  public void testWriteBinaryAsObject() {
    final byte[] data = new byte[] {1, 2, 3, 4};
    MessageFormatter messageFormatter =
        new MsgPackWriter(
            newBuffer(
                25,
                (messageCount, buffy) -> {
                  MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffy);
                  try {
                    int length = unpacker.unpackBinaryHeader();
                    assertEquals(4, length);
                    assertArrayEquals(data, unpacker.readPayload(length));
                  } catch (IOException e) {
                    Assertions.fail(e.getMessage());
                  }
                }));
    messageFormatter.format(data, (ba, writable) -> writable.writeObject(ba, null));
    messageFormatter.flush();
  }

  @Test
  public void testWriteByteBuffer() {
    final byte[] data = new byte[] {1, 2, 3, 4};
    MessageFormatter messageFormatter =
        new MsgPackWriter(
            newBuffer(
                25,
                (messageCount, buffy) -> {
                  MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffy);
                  try {
                    int length = unpacker.unpackBinaryHeader();
                    assertEquals(4, length);
                    assertArrayEquals(data, unpacker.readPayload(length));
                  } catch (IOException e) {
                    Assertions.fail(e.getMessage());
                  }
                }));
    messageFormatter.format(ByteBuffer.wrap(data), (bb, writable) -> writable.writeBinary(bb));
    messageFormatter.flush();
  }

  @Test
  public void testWriteByteBufferAsObject() {
    final byte[] data = new byte[] {1, 2, 3, 4};
    MessageFormatter messageFormatter =
        new MsgPackWriter(
            newBuffer(
                25,
                (messageCount, buffy) -> {
                  MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffy);
                  try {
                    int length = unpacker.unpackBinaryHeader();
                    assertEquals(4, length);
                    assertArrayEquals(data, unpacker.readPayload(length));
                  } catch (IOException e) {
                    Assertions.fail(e.getMessage());
                  }
                }));
    messageFormatter.format(
        ByteBuffer.wrap(data), (bb, writable) -> writable.writeObject(bb, null));
    messageFormatter.flush();
  }

  @Test
  public void testWriteNull() {
    MessageFormatter messageFormatter =
        new MsgPackWriter(
            newBuffer(
                25,
                (messageCount, buffy) -> {
                  MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffy);
                  try {
                    unpacker.unpackNil();
                  } catch (IOException e) {
                    Assertions.fail(e.getMessage());
                  }
                }));
    messageFormatter.format(null, (x, w) -> w.writeObject(x, null));
    messageFormatter.flush();
  }

  @Test
  public void testWriteBooleanAsObject() {
    MessageFormatter messageFormatter =
        new MsgPackWriter(
            newBuffer(
                25,
                (messageCount, buffy) -> {
                  MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffy);
                  try {
                    assertTrue(unpacker.unpackBoolean());
                  } catch (IOException e) {
                    Assertions.fail(e.getMessage());
                  }
                }));
    messageFormatter.format(true, (x, w) -> w.writeObject(x, null));
    messageFormatter.flush();
  }

  @Test
  public void testWriteBoolean() {
    MessageFormatter messageFormatter =
        new MsgPackWriter(
            newBuffer(
                25,
                (messageCount, buffy) -> {
                  MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffy);
                  try {
                    assertTrue(unpacker.unpackBoolean());
                  } catch (IOException e) {
                    Assertions.fail(e.getMessage());
                  }
                }));
    messageFormatter.format(true, (x, w) -> w.writeBoolean(x));
    messageFormatter.flush();
  }

  @Test
  public void testWriteGenericNumber() {
    final BigDecimal data = BigDecimal.valueOf(47.11);
    MessageFormatter messageFormatter =
        new MsgPackWriter(
            newBuffer(
                25,
                (messageCount, buffy) -> {
                  MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffy);
                  try {
                    assertEquals(data.doubleValue(), unpacker.unpackDouble(), 0);
                  } catch (IOException e) {
                    Assertions.fail(e.getMessage());
                  }
                }));
    messageFormatter.format(data, (Mapper<Number>) (x, w) -> w.writeObject(x, null));
    messageFormatter.flush();
  }

  @Test
  public void testWriteCharArray() {
    final String data = "xyz";
    MessageFormatter messageFormatter =
        new MsgPackWriter(
            newBuffer(
                25,
                (messageCount, buffy) -> {
                  MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffy);
                  try {
                    assertEquals(data, unpacker.unpackString());
                  } catch (IOException e) {
                    Assertions.fail(e.getMessage());
                  }
                }));
    messageFormatter.format(data.toCharArray(), (x, w) -> w.writeObject(x, null));
    messageFormatter.flush();
  }

  @Test
  public void testWriteUTF8ByteString() {
    final UTF8BytesString utf8BytesString = UTF8BytesString.create("xyz");
    MessageFormatter messageFormatter =
        new MsgPackWriter(
            newBuffer(
                25,
                (messageCount, buffy) -> {
                  MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffy);
                  try {
                    assertEquals("xyz", unpacker.unpackString());
                  } catch (IOException e) {
                    Assertions.fail(e.getMessage());
                  }
                }));
    messageFormatter.format(utf8BytesString, (x, w) -> w.writeObject(x, null));
    messageFormatter.flush();
  }

  @Test
  public void testWriteBooleanArray() {
    final boolean[] data = new boolean[] {true, false, true, true};
    MessageFormatter messageFormatter =
        new MsgPackWriter(
            newBuffer(
                25,
                (messageCount, buffy) -> {
                  MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffy);
                  try {
                    assertEquals(4, unpacker.unpackArrayHeader());
                    for (boolean datum : data) {
                      assertEquals(datum, unpacker.unpackBoolean());
                    }
                  } catch (IOException e) {
                    Assertions.fail(e.getMessage());
                  }
                }));
    messageFormatter.format(data, (x, w) -> w.writeObject(x, null));
    messageFormatter.flush();
  }

  @Test
  public void testWriteFloatArray() {
    final float[] data = new float[] {0.1f, 0.2f, 0.3f, 0.4f};
    MessageFormatter messageFormatter =
        new MsgPackWriter(
            newBuffer(
                100,
                (messageCount, buffy) -> {
                  MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffy);
                  try {
                    assertEquals(4, unpacker.unpackArrayHeader());
                    for (float datum : data) {
                      assertEquals(datum, unpacker.unpackFloat(), 0.001);
                    }
                  } catch (IOException e) {
                    Assertions.fail(e.getMessage());
                  }
                }));
    messageFormatter.format(data, (x, w) -> w.writeObject(x, null));
    messageFormatter.flush();
  }

  @Test
  public void testWriteDoubleArray() {
    final double[] data = new double[] {0.1f, 0.2f, 0.3f, 0.4f};
    MessageFormatter messageFormatter =
        new MsgPackWriter(
            newBuffer(
                100,
                (messageCount, buffy) -> {
                  MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffy);
                  try {
                    assertEquals(4, unpacker.unpackArrayHeader());
                    for (double datum : data) {
                      assertEquals(datum, unpacker.unpackDouble(), 0.001);
                    }
                  } catch (IOException e) {
                    Assertions.fail(e.getMessage());
                  }
                }));
    messageFormatter.format(data, (x, w) -> w.writeObject(x, null));
    messageFormatter.flush();
  }

  @Test
  public void testWriteLongArray() {
    final long[] data = new long[] {1, 2, 3, 4};
    MessageFormatter messageFormatter =
        new MsgPackWriter(
            newBuffer(
                100,
                (messageCount, buffy) -> {
                  MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffy);
                  try {
                    assertEquals(4, unpacker.unpackArrayHeader());
                    for (long datum : data) {
                      assertEquals(datum, unpacker.unpackLong());
                    }
                  } catch (IOException e) {
                    Assertions.fail(e.getMessage());
                  }
                }));
    messageFormatter.format(data, (x, w) -> w.writeObject(x, null));
    messageFormatter.flush();
  }

  @Test
  public void testWriteIntArray() {
    final int[] data = new int[] {1, 2, 3, 4};
    MessageFormatter messageFormatter =
        new MsgPackWriter(
            newBuffer(
                100,
                (messageCount, buffy) -> {
                  MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffy);
                  try {
                    assertEquals(4, unpacker.unpackArrayHeader());
                    for (int datum : data) {
                      assertEquals(datum, unpacker.unpackInt());
                    }
                  } catch (IOException e) {
                    Assertions.fail(e.getMessage());
                  }
                }));
    messageFormatter.format(data, (x, w) -> w.writeObject(x, null));
    messageFormatter.flush();
  }

  @Test
  public void testWriteShortArray() {
    final short[] data = new short[] {1, 2, 3, 4};
    MessageFormatter messageFormatter =
        new MsgPackWriter(
            newBuffer(
                100,
                (messageCount, buffy) -> {
                  MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffy);
                  try {
                    assertEquals(4, unpacker.unpackArrayHeader());
                    for (short datum : data) {
                      assertEquals(datum, unpacker.unpackInt());
                    }
                  } catch (IOException e) {
                    Assertions.fail(e.getMessage());
                  }
                }));
    messageFormatter.format(data, (x, w) -> w.writeObject(x, null));
    messageFormatter.flush();
  }

  @Test
  public void testWriteLongBoxed() {
    final long data = 1234L;
    MessageFormatter messageFormatter =
        new MsgPackWriter(
            newBuffer(
                100,
                (messageCount, buffy) -> {
                  MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffy);
                  try {
                    assertEquals(data, unpacker.unpackLong());
                  } catch (IOException e) {
                    Assertions.fail(e.getMessage());
                  }
                }));
    messageFormatter.format(data, (x, w) -> w.writeObject(x, null));
    messageFormatter.flush();
  }

  @Test
  public void testWriteLongPrimitive() {
    final long data = 1234L;
    MessageFormatter messageFormatter =
        new MsgPackWriter(
            newBuffer(
                100,
                (messageCount, buffy) -> {
                  MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffy);
                  try {
                    assertEquals(data, unpacker.unpackLong());
                    assertEquals(data, unpacker.unpackLong());
                  } catch (IOException e) {
                    Assertions.fail(e.getMessage());
                  }
                }));
    messageFormatter.format(
        data,
        (x, w) -> {
          w.writeLong(x);
          w.writeSignedLong(x);
        });
    messageFormatter.flush();
  }

  @Test
  public void testWriteNegativeLongPrimitive() {
    final long data = -1234L;
    MessageFormatter messageFormatter =
        new MsgPackWriter(
            newBuffer(
                100,
                (messageCount, buffy) -> {
                  MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffy);
                  try {
                    assertEquals(data, unpacker.unpackLong());
                    assertEquals(data, unpacker.unpackLong());
                    // Can't unpack unsigned long directly as the unpacker refuses negative values
                    assertEquals(data, unpacker.unpackValue().asNumberValue().toLong());
                  } catch (IOException e) {
                    Assertions.fail(e.getMessage());
                  }
                }));
    messageFormatter.format(
        data,
        (x, w) -> {
          w.writeLong(x);
          w.writeSignedLong(x);
          w.writeUnsignedLong(x);
        });
    messageFormatter.flush();
  }

  @Test
  public void testWriteIntBoxed() {
    final int data = 1234;
    MessageFormatter messageFormatter =
        new MsgPackWriter(
            newBuffer(
                100,
                (messageCount, buffy) -> {
                  MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffy);
                  try {
                    assertEquals(data, unpacker.unpackInt());
                  } catch (IOException e) {
                    Assertions.fail(e.getMessage());
                  }
                }));
    messageFormatter.format(data, (x, w) -> w.writeObject(x, null));
    messageFormatter.flush();
  }

  @Test
  public void testWriteIntPrimitive() {
    final int data = 1234;
    MessageFormatter messageFormatter =
        new MsgPackWriter(
            newBuffer(
                100,
                (messageCount, buffy) -> {
                  MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffy);
                  try {
                    assertEquals(data, unpacker.unpackInt());
                  } catch (IOException e) {
                    Assertions.fail(e.getMessage());
                  }
                }));
    messageFormatter.format(data, (x, w) -> w.writeInt(x));
    messageFormatter.flush();
  }

  @Test
  public void testWriteShortBoxed() {
    final short data = 1234;
    MessageFormatter messageFormatter =
        new MsgPackWriter(
            newBuffer(
                100,
                (messageCount, buffy) -> {
                  MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffy);
                  try {
                    assertEquals(data, unpacker.unpackInt());
                  } catch (IOException e) {
                    Assertions.fail(e.getMessage());
                  }
                }));
    messageFormatter.format(data, (x, w) -> w.writeObject(x, null));
    messageFormatter.flush();
  }

  @Test
  public void testUnknownObject() {
    final Object data = Codec.INSTANCE;
    MessageFormatter messageFormatter =
        new MsgPackWriter(
            newBuffer(
                100,
                (messageCount, buffy) -> {
                  MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffy);
                  try {
                    assertEquals(data.toString(), unpacker.unpackString());
                  } catch (IOException e) {
                    Assertions.fail(e.getMessage());
                  }
                }));
    messageFormatter.format(data, (x, w) -> w.writeObject(x, null));
    messageFormatter.flush();
  }

  @Test
  public void testWriteObjectArray() {
    final Object[] data = new Object[] {"foo", "bar"};
    MessageFormatter messageFormatter =
        new MsgPackWriter(
            newBuffer(
                100,
                (messageCount, buffy) -> {
                  MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffy);
                  try {
                    assertEquals(data.length, unpacker.unpackArrayHeader());
                    assertEquals(data[0].toString(), unpacker.unpackString());
                    assertEquals(data[1].toString(), unpacker.unpackString());
                  } catch (IOException e) {
                    Assertions.fail(e.getMessage());
                  }
                }));
    messageFormatter.format(data, (x, w) -> w.writeObject(x, null));
    messageFormatter.flush();
  }

  @Test
  public void testWriteStringUTF8BytesString() {
    UTF8BytesString value = UTF8BytesString.create("foobár");
    MsgPackWriter writer =
        new MsgPackWriter(
            newBuffer(
                20,
                (messageCount, buffer) -> {
                  MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffer);
                  try {
                    assertEquals(unpacker.unpackString(), "foobár");
                    assertEquals(unpacker.unpackString(), "foobár");
                  } catch (IOException e) {
                    Assertions.fail(e.getMessage());
                  }
                }));
    writer.writeObjectString(value, null);
    writer.writeString(value, null);
  }

  @Test
  public void testWriteStringNull() {
    MsgPackWriter writer =
        new MsgPackWriter(
            newBuffer(
                20,
                (messageCount, buffer) -> {
                  MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffer);
                  try {
                    unpacker.unpackNil();
                    unpacker.unpackNil();
                  } catch (IOException e) {
                    Assertions.fail(e.getMessage());
                  }
                }));
    writer.writeObjectString(null, null);
    writer.writeString(null, null);
  }

  @Test
  public void testWriteObjectStringGeneralPath() {
    Object value =
        new Object() {
          @Override
          public String toString() {
            return "foobár";
          }
        };
    MsgPackWriter writer =
        new MsgPackWriter(
            newBuffer(
                40,
                (messageCount, buffer) -> {
                  MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffer);
                  try {
                    assertEquals(unpacker.unpackString(), "foobár");
                    assertEquals(unpacker.unpackString(), "foobàr");
                  } catch (IOException e) {
                    Assertions.fail(e.getMessage());
                  }
                }));
    writer.writeObjectString(value, null);
    writer.writeObjectString(value, s -> "foobàr".getBytes(StandardCharsets.UTF_8));
  }

  @Test
  public void testWriteStringGeneralCharSequence() {
    MsgPackWriter writer =
        new MsgPackWriter(
            newBuffer(
                10,
                (messageCount, buffer) -> {
                  MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffer);
                  try {
                    assertEquals(unpacker.unpackString(), "foobár");
                  } catch (IOException e) {
                    Assertions.fail(e.getMessage());
                  }
                }));
    CharBuffer charSeq = CharBuffer.wrap("foobár");
    writer.writeString(charSeq, null);
  }

  @Test
  public void testWriteStringEncodingCache() {
    MsgPackWriter writer =
        new MsgPackWriter(
            newBuffer(
                10,
                (messageCount, buffer) -> {
                  MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffer);
                  try {
                    assertEquals(unpacker.unpackString(), "foobár");
                  } catch (IOException e) {
                    Assertions.fail(e.getMessage());
                  }
                }));
    writer.writeString("", s -> "foobár".getBytes(StandardCharsets.UTF_8));
  }

  @Test
  public void testStartArray() {
    MsgPackWriter writer =
        new MsgPackWriter(
            newBuffer(
                10,
                (messageCount, buffer) -> {
                  MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffer);
                  try {
                    assertEquals(unpacker.unpackArrayHeader(), 1);
                    assertEquals(unpacker.unpackArrayHeader(), 0xFFFF);
                    assertEquals(unpacker.unpackArrayHeader(), 0x10000);
                    assertEquals(unpacker.unpackArrayHeader(), 1);
                  } catch (IOException e) {
                    Assertions.fail(e.getMessage());
                  }
                }));
    writer.startArray(1);
    writer.startArray(0xFFFF);
    writer.startArray(0x10000);
    writer.startStruct(1);
  }

  @Test
  public void testStartMap() {
    MsgPackWriter writer =
        new MsgPackWriter(
            newBuffer(
                10,
                (messageCount, buffer) -> {
                  MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffer);
                  try {
                    assertEquals(unpacker.unpackMapHeader(), 1);
                    assertEquals(unpacker.unpackMapHeader(), 0xFFFF);
                    assertEquals(unpacker.unpackMapHeader(), 0x10000);
                  } catch (IOException e) {
                    Assertions.fail(e.getMessage());
                  }
                }));
    writer.startMap(1);
    writer.startMap(0xFFFF);
    writer.startMap(0x10000);
  }

  @Test
  public void testStartStringHeader() {
    MsgPackWriter writer =
        new MsgPackWriter(
            newBuffer(
                10,
                (messageCount, buffer) -> {
                  MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffer);
                  try {
                    assertEquals(unpacker.unpackRawStringHeader(), 1);
                    assertEquals(unpacker.unpackRawStringHeader(), 0xFFFF);
                    assertEquals(unpacker.unpackRawStringHeader(), 0x10000);
                  } catch (IOException e) {
                    Assertions.fail(e.getMessage());
                  }
                }));
    writer.writeStringHeader(1);
    writer.writeStringHeader(0xFFFF);
    writer.writeStringHeader(0x10000);
  }

  private StreamingBuffer newBuffer(int capacity, ByteBufferConsumer consumer) {
    return new FlushingBuffer(capacity, consumer);
  }
}

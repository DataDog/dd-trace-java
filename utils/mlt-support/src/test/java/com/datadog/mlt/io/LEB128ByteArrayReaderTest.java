package com.datadog.mlt.io;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class LEB128ByteArrayReaderTest {
  @Test
  void sanity() {
    LEB128ByteArrayWriter writer = new LEB128ByteArrayWriter(2048);
    byte b = Byte.MAX_VALUE - 1;
    char c = Character.MAX_VALUE - 1;
    short s = Short.MAX_VALUE - 1;
    int i = Integer.MAX_VALUE - 1;
    long l = Long.MAX_VALUE - 1;
    float f = Float.MAX_VALUE - 1;
    double d = Double.MAX_VALUE - 1;
    String t = "hello";

    writer.writeByte(b);
    writer.writeChar(c);
    writer.writeShort(s);
    writer.writeShortRaw(s);
    writer.writeInt(i);
    writer.writeIntRaw(i);
    writer.writeLong(l);
    writer.writeLongRaw(l);
    writer.writeFloat(f);
    writer.writeDouble(d);
    writer.writeBoolean(true);
    writer.writeBoolean(false);
    writer.writeUTF(t);
    assertTrue(writer.length() > 0);
    assertTrue(writer.length() >= writer.position());

    LEB128ByteArrayReader reader = new LEB128ByteArrayReader(writer.toByteArray());
    assertEquals(b, reader.readByte());
    assertEquals(c, reader.readChar());
    assertEquals(s, reader.readShort());
    assertEquals(s, reader.readShortRaw());
    assertEquals(i, reader.readInt());
    assertEquals(i, reader.readIntRaw());
    assertEquals(l, reader.readLong());
    assertEquals(l, reader.readLongRaw());
    assertEquals(f, reader.readFloat());
    assertEquals(d, reader.readDouble());
    assertTrue(reader.readBoolean());
    assertFalse(reader.readBoolean());
    assertEquals(t, reader.readUTF());

    assertFalse(reader.hasMore());
    assertEquals(writer.position(), reader.size());
    reader.reset();
    assertTrue(reader.hasMore());
    assertEquals(writer.position(), reader.size());
    assertEquals(0, reader.position());
  }
}

package datadog.trace.core.serialization;

import datadog.trace.core.StringTables;
import java.io.IOException;
import java.math.BigInteger;
import org.msgpack.core.MessagePacker;

public class MsgpackFormatWriter extends FormatWriter<MessagePacker> {
  public static MsgpackFormatWriter MSGPACK_WRITER = new MsgpackFormatWriter();

  @Override
  public void writeKey(final String key, final MessagePacker destination) throws IOException {
    destination.packString(key);
  }

  @Override
  public void writeListHeader(final int size, final MessagePacker destination) throws IOException {
    destination.packArrayHeader(size);
  }

  @Override
  public void writeListFooter(final MessagePacker destination) {}

  @Override
  public void writeMapHeader(final int size, final MessagePacker destination) throws IOException {
    destination.packMapHeader(size);
  }

  @Override
  public void writeMapFooter(final MessagePacker destination) {}

  @Override
  public void writeString(final String key, final String value, final MessagePacker destination)
      throws IOException {
    writeStringUTF8(key, destination);
    if (value == null) {
      destination.packNil();
    } else {
      destination.packString(value);
    }
  }

  @Override
  public void writeTag(String key, String value, MessagePacker destination) throws IOException {
    // there's a good chance that the tag value will be something we have interned
    writeStringUTF8(key, destination);
    if (value == null) {
      destination.packNil();
    } else {
      writeStringUTF8(value, destination);
    }
  }

  @Override
  public void writeShort(final String key, final short value, final MessagePacker destination)
      throws IOException {
    writeStringUTF8(key, destination);
    destination.packShort(value);
  }

  @Override
  public void writeByte(final String key, final byte value, final MessagePacker destination)
      throws IOException {
    writeStringUTF8(key, destination);
    destination.packByte(value);
  }

  @Override
  public void writeInt(final String key, final int value, final MessagePacker destination)
      throws IOException {
    writeStringUTF8(key, destination);
    destination.packInt(value);
  }

  @Override
  public void writeLong(final String key, final long value, final MessagePacker destination)
      throws IOException {
    writeStringUTF8(key, destination);
    destination.packLong(value);
  }

  @Override
  public void writeFloat(final String key, final float value, final MessagePacker destination)
      throws IOException {
    writeStringUTF8(key, destination);
    destination.packFloat(value);
  }

  @Override
  public void writeDouble(final String key, final double value, final MessagePacker destination)
      throws IOException {
    writeStringUTF8(key, destination);
    destination.packDouble(value);
  }

  @Override
  public void writeBigInteger(
      final String key, final BigInteger value, final MessagePacker destination)
      throws IOException {
    writeStringUTF8(key, destination);
    if (value == null) {
      destination.packNil();
    } else {
      destination.packBigInteger(value);
    }
  }

  private static void writeStringUTF8(String value, MessagePacker destination) throws IOException {
    if (value.length() < 256) {
      byte[] utf8 = StringTables.getBytesUTF8(value);
      destination.packRawStringHeader(utf8.length);
      destination.addPayload(utf8);
    } else {
      destination.packString(value);
    }
  }
}

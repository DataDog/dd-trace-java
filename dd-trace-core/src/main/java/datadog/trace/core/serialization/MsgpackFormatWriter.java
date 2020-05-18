package datadog.trace.core.serialization;

import datadog.trace.core.StringTables;
import java.io.IOException;
import java.math.BigInteger;
import org.msgpack.core.MessagePacker;

public class MsgpackFormatWriter extends FormatWriter<MessagePacker> {
  public static MsgpackFormatWriter MSGPACK_WRITER = new MsgpackFormatWriter();

  @Override
  public void writeKey(byte[] key, MessagePacker destination) throws IOException {
    destination.packRawStringHeader(key.length);
    destination.addPayload(key);
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
  public void writeString(byte[] key, String value, MessagePacker destination) throws IOException {
    writeKey(key, destination);
    if (value == null) {
      destination.packNil();
    } else {
      destination.packString(value);
    }
  }

  @Override
  public void writeShort(byte[] key, short value, MessagePacker destination) throws IOException {
    writeKey(key, destination);
    destination.packShort(value);
  }

  @Override
  public void writeByte(byte[] key, byte value, MessagePacker destination) throws IOException {
    writeKey(key, destination);
    destination.packByte(value);
  }

  @Override
  public void writeInt(byte[] key, int value, MessagePacker destination) throws IOException {
    writeKey(key, destination);
    destination.packInt(value);
  }

  @Override
  public void writeLong(byte[] key, long value, MessagePacker destination) throws IOException {
    writeKey(key, destination);
    destination.packLong(value);
  }

  @Override
  public void writeFloat(byte[] key, float value, MessagePacker destination) throws IOException {
    writeKey(key, destination);
    destination.packFloat(value);
  }

  @Override
  public void writeDouble(byte[] key, double value, MessagePacker destination) throws IOException {
    writeKey(key, destination);
    destination.packDouble(value);
  }

  @Override
  public void writeBigInteger(byte[] key, BigInteger value, MessagePacker destination)
      throws IOException {
    writeKey(key, destination);
    if (value == null) {
      destination.packNil();
    } else {
      destination.packBigInteger(value);
    }
  }

  private static void writeStringUTF8(String value, MessagePacker destination) throws IOException {
    if (value.length() < 256) {
      byte[] interned = StringTables.getBytesUTF8(value);
      if (null != interned) {
        destination.packRawStringHeader(interned.length);
        destination.addPayload(interned);
        return;
      }
    }
    destination.packString(value);
  }
}

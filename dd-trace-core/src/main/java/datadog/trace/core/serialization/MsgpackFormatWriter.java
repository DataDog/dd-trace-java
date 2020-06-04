package datadog.trace.core.serialization;

import datadog.trace.api.DDId;
import datadog.trace.core.StringTables;
import java.io.IOException;
import org.msgpack.core.MessagePacker;

public class MsgpackFormatWriter extends FormatWriter<MessagePacker> {
  public static MsgpackFormatWriter MSGPACK_WRITER = new MsgpackFormatWriter();

  @Override
  public void writeKey(final byte[] key, MessagePacker destination) throws IOException {
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
  public void writeString(final byte[] key, final String value, MessagePacker destination)
      throws IOException {
    writeKey(key, destination);
    if (value == null) {
      destination.packNil();
    } else {
      destination.packString(value);
    }
  }

  @Override
  public void writeTag(final byte[] key, final String value, final MessagePacker destination)
      throws IOException {
    writeKey(key, destination);
    writeUTF8Tag(value, destination);
  }

  @Override
  public void writeShort(final byte[] key, final short value, final MessagePacker destination)
      throws IOException {
    writeKey(key, destination);
    destination.packShort(value);
  }

  @Override
  public void writeByte(final byte[] key, final byte value, final MessagePacker destination)
      throws IOException {
    writeKey(key, destination);
    destination.packByte(value);
  }

  @Override
  public void writeInt(final byte[] key, final int value, final MessagePacker destination)
      throws IOException {
    writeKey(key, destination);
    destination.packInt(value);
  }

  @Override
  public void writeLong(final byte[] key, final long value, final MessagePacker destination)
      throws IOException {
    writeKey(key, destination);
    destination.packLong(value);
  }

  @Override
  public void writeFloat(final byte[] key, final float value, final MessagePacker destination)
      throws IOException {
    writeKey(key, destination);
    destination.packFloat(value);
  }

  @Override
  public void writeDouble(final byte[] key, final double value, final MessagePacker destination)
      throws IOException {
    writeKey(key, destination);
    destination.packDouble(value);
  }

  @Override
  public void writeId(byte[] key, DDId id, MessagePacker destination) throws IOException {
    // Since the Datadog agent will decode the long as an unsigned 64 bit integer even
    // if it's sent as a signed 64 bit integer, we can just use writeLong here
    writeLong(key, id.toLong(), destination);
  }

  private static void writeUTF8Tag(final String value, final MessagePacker destination)
      throws IOException {
    if (null == value) {
      destination.packNil();
    } else {
      byte[] interned = StringTables.getTagBytesUTF8(value);
      if (null != interned) {
        destination.packRawStringHeader(interned.length);
        destination.addPayload(interned);
      } else {
        destination.packString(value);
      }
    }
  }
}

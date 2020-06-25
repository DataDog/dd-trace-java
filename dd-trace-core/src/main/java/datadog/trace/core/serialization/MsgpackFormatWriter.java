package datadog.trace.core.serialization;

import datadog.trace.api.DDId;
import datadog.trace.core.StringTables;
import datadog.trace.core.util.LRUCache;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
    cachedWriteString(value, destination);
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

  @Override
  public void writeObjectAsString(byte[] key, Object value, MessagePacker destination)
      throws IOException {
    writeKey(key, destination);
    if (value instanceof Long || value instanceof Integer) {
      writeLongAsString(((Number) value).longValue(), destination);
    } else if (value instanceof String) {
      cachedWriteString((String) value, destination);
    } else {
      cachedWriteString(String.valueOf(value), destination);
    }
  }

  private void writeUTF8Tag(final String value, final MessagePacker destination)
      throws IOException {
    if (null == value) {
      destination.packNil();
    } else {
      byte[] interned = StringTables.getTagBytesUTF8(value);
      if (null != interned) {
        destination.packRawStringHeader(interned.length);
        destination.addPayload(interned);
      } else {
        cachedWriteString(value, destination);
      }
    }
  }

  // Storing the last used 128 entries of max 32 characters for tags
  // The initial capacity of 256 is to make sure that the underlying HashMap never resizes
  private final LRUCache<String, byte[]> stringCache = new LRUCache<>(256, 128);

  private void cachedWriteString(final String value, final MessagePacker destination)
      throws IOException {
    if (null == value) {
      destination.packNil();
    } else {
      int len = value.length();
      if (len > 0 && len <= 32) {
        byte[] bytes = stringCache.get(value);
        if (bytes == null) {
          bytes = value.getBytes(StandardCharsets.UTF_8);
          stringCache.put(value, bytes);
        }
        destination.packRawStringHeader(bytes.length);
        destination.addPayload(bytes);
      } else {
        destination.packString(value);
      }
    }
  }

  private static final byte[] DIGIT_TENS = {
    '0', '0', '0', '0', '0', '0', '0', '0', '0', '0',
    '1', '1', '1', '1', '1', '1', '1', '1', '1', '1',
    '2', '2', '2', '2', '2', '2', '2', '2', '2', '2',
    '3', '3', '3', '3', '3', '3', '3', '3', '3', '3',
    '4', '4', '4', '4', '4', '4', '4', '4', '4', '4',
    '5', '5', '5', '5', '5', '5', '5', '5', '5', '5',
    '6', '6', '6', '6', '6', '6', '6', '6', '6', '6',
    '7', '7', '7', '7', '7', '7', '7', '7', '7', '7',
    '8', '8', '8', '8', '8', '8', '8', '8', '8', '8',
    '9', '9', '9', '9', '9', '9', '9', '9', '9', '9',
  };

  private static final byte[] DIGIT_ONES = {
    '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
    '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
    '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
    '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
    '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
    '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
    '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
    '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
    '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
    '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
  };

  private final byte[] numberByteArray = new byte[20]; // this is max long digits and sign

  private void writeLongAsString(final long value, final MessagePacker destination)
      throws IOException {
    int pos = 20; // start from the end
    long l = value;
    boolean negative = (l < 0);
    if (!negative) {
      l = -l; // do the conversion on negative values to not overflow Long.MIN_VALUE
    }

    int r;
    // convert 2 digits per iteration with longs until quotient fits into an int
    long lq;
    while (l <= Integer.MIN_VALUE) {
      lq = l / 100;
      r = (int) ((lq * 100) - l);
      l = lq;
      numberByteArray[--pos] = DIGIT_ONES[r];
      numberByteArray[--pos] = DIGIT_TENS[r];
    }

    // convert 2 digits per iteration with ints
    int iq;
    int i = (int) l;
    while (i <= -100) {
      iq = i / 100;
      r = (iq * 100) - i;
      i = iq;
      numberByteArray[--pos] = DIGIT_ONES[r];
      numberByteArray[--pos] = DIGIT_TENS[r];
    }

    // now there are at most two digits left
    iq = i / 10;
    r = (iq * 10) - i;
    numberByteArray[--pos] = (byte) ('0' + r);

    // if there is something left it is the remaining digit
    if (iq < 0) {
      numberByteArray[--pos] = (byte) ('0' - iq);
    }

    if (negative) {
      numberByteArray[--pos] = (byte) '-';
    }

    int len = 20 - pos;
    destination.packRawStringHeader(len);
    destination.addPayload(numberByteArray, pos, len);
  }
}

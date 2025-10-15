package datadog.communication.serialization.msgpack;

import static java.nio.charset.StandardCharsets.UTF_8;

import datadog.communication.serialization.Codec;
import datadog.communication.serialization.EncodingCache;
import datadog.communication.serialization.Mapper;
import datadog.communication.serialization.StreamingBuffer;
import datadog.communication.serialization.ValueWriter;
import datadog.communication.serialization.WritableFormatter;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Not thread-safe (use one per thread). */
public class MsgPackWriter implements WritableFormatter {

  private static final Logger log = LoggerFactory.getLogger(MsgPackWriter.class);

  // see https://github.com/msgpack/msgpack/blob/master/spec.md
  public static final byte NULL = (byte) 0xC0;

  public static final byte FALSE = (byte) 0xC2;
  public static final byte TRUE = (byte) 0xC3;

  public static final byte UINT8 = (byte) 0xCC;
  public static final byte UINT16 = (byte) 0xCD;
  public static final byte UINT32 = (byte) 0xCE;
  public static final byte UINT64 = (byte) 0xCF;

  public static final byte INT8 = (byte) 0xD0;
  public static final byte INT16 = (byte) 0xD1;
  public static final byte INT32 = (byte) 0xD2;
  public static final byte INT64 = (byte) 0xD3;

  public static final byte FLOAT32 = (byte) 0xCA;
  public static final byte FLOAT64 = (byte) 0xCB;

  public static final byte STR8 = (byte) 0xD9;
  public static final byte STR16 = (byte) 0xDA;
  public static final byte STR32 = (byte) 0xDB;

  public static final byte BIN8 = (byte) 0xC4;
  public static final byte BIN16 = (byte) 0xC5;
  public static final byte BIN32 = (byte) 0xC6;

  public static final byte ARRAY16 = (byte) 0xDC;
  public static final byte ARRAY32 = (byte) 0xDD;

  public static final byte MAP16 = (byte) 0xDE;
  public static final byte MAP32 = (byte) 0xDF;

  public static final int NEGFIXNUM = 0xE0;
  public static final int FIXSTR = 0xA0;
  public static final int FIXARRAY = 0x90;
  public static final int FIXMAP = 0x80;

  private final Codec codec;

  private final StreamingBuffer buffer;

  public MsgPackWriter(StreamingBuffer buffer) {
    this(Codec.INSTANCE, buffer);
  }

  public MsgPackWriter(Codec codec, StreamingBuffer buffer) {
    this.codec = codec;
    this.buffer = buffer;
  }

  @Override
  public void flush() {
    if (buffer.isDirty()) {
      buffer.flush();
    }
  }

  @Override
  public <T> boolean format(T message, Mapper<T> mapper) {
    try {
      mapper.map(message, this);
      buffer.mark();
      return true;
    } catch (BufferOverflowException overflow) {
      // if the buffer has finite capacity, it will overflow
      // if we tried to serialise a message larger than the
      // max capacity, then reject the message
      if (buffer.flush()) {
        try {
          mapper.reset();
          mapper.map(message, this);
          buffer.mark();
          return true;
        } catch (BufferOverflowException fatal) {
          log.debug(
              "dropping message because its serialized size is too large (> {}MB)",
              (buffer.capacity() >>> 20));
        }
      }
      buffer.reset();
      return false;
    }
  }

  // NOTE - implementations pulled up to this level should
  // not write directly to the buffer

  @Override
  public void writeMap(Map<? extends CharSequence, ?> map, EncodingCache encodingCache) {
    startMap(map.size());
    for (Map.Entry<? extends CharSequence, ?> entry : map.entrySet()) {
      writeString(entry.getKey(), encodingCache);
      writeObject(entry.getValue(), encodingCache);
    }
  }

  @Override
  @SuppressWarnings({"rawtypes", "unchecked"})
  public void writeObject(Object value, EncodingCache encodingCache) {
    // unpeel a very common case, but should try to move away from sending
    // UTF8BytesString down this codepath at all
    if (value instanceof UTF8BytesString) {
      writeUTF8((UTF8BytesString) value);
    } else if (null == value) {
      writeNull();
    } else {
      ValueWriter writer = codec.get(value.getClass());
      writer.write(value, this, encodingCache);
    }
  }

  @Override
  @SuppressWarnings({"rawtypes", "unchecked"})
  public void writeObjectString(Object value, EncodingCache encodingCache) {
    // unpeel a very common case, but should try to move away from sending
    // UTF8BytesString down this codepath at all
    if (value instanceof UTF8BytesString) {
      writeUTF8((UTF8BytesString) value);
    } else if (null == value) {
      writeNull();
    } else {
      String s = String.valueOf(value);
      if (null != encodingCache) {
        byte[] utf8 = encodingCache.encode(s);
        if (null != utf8) {
          writeUTF8(utf8);
          return;
        }
      }
      writeUTF8(s.getBytes(UTF_8));
    }
  }

  @Override
  public void writeNull() {
    buffer.put(NULL);
  }

  @Override
  public void writeBoolean(boolean value) {
    buffer.put(value ? TRUE : FALSE);
  }

  @Override
  public void writeString(CharSequence s, EncodingCache encodingCache) {
    if (null == s) {
      writeNull();
    } else {
      if (null != encodingCache) {
        byte[] utf8 = encodingCache.encode(s);
        if (null != utf8) {
          writeUTF8(utf8);
          return;
        }
      }
      if (s instanceof UTF8BytesString) {
        writeUTF8((UTF8BytesString) s);
      } else {
        writeUTF8(String.valueOf(s).getBytes(UTF_8));
      }
    }
  }

  @Override
  public void writeUTF8(byte[] string, int offset, int length) {
    writeStringHeader(length);
    buffer.put(string, offset, length);
  }

  @Override
  public void writeUTF8(byte[] string) {
    writeUTF8(string, 0, string.length);
  }

  @Override
  public void writeUTF8(UTF8BytesString string) {
    writeStringHeader(string.encodedLength());
    buffer.put(string.getUtf8Bytes());
  }

  @Override
  public void writeBinary(byte[] binary) {
    writeBinaryHeader(binary.length);
    buffer.put(binary);
  }

  @Override
  public void writeBinary(byte[] binary, int offset, int length) {
    writeBinaryHeader(length);
    buffer.put(binary, offset, length);
  }

  @Override
  public void writeBinary(ByteBuffer binary) {
    ByteBuffer slice = binary.slice();
    writeBinaryHeader(slice.limit() - slice.position());
    buffer.put(slice);
  }

  @Override
  public void writeInt(int value) {
    if (value < 0) {
      switch (Integer.numberOfLeadingZeros(~value)) {
        case 0:
        case 1:
        case 2:
        case 3:
        case 4:
        case 5:
        case 6:
        case 7:
        case 8:
        case 9:
        case 10:
        case 11:
        case 12:
        case 13:
        case 14:
        case 15:
        case 16:
          buffer.put(INT32);
          buffer.putInt(value);
          break;
        case 17:
        case 18:
        case 19:
        case 20:
        case 21:
        case 22:
        case 23:
        case 24:
          buffer.put(INT16);
          buffer.putChar((char) value);
          break;
        case 25:
        case 26:
          buffer.put(INT8);
          buffer.put((byte) value);
          break;
        case 27:
        case 28:
        case 29:
        case 30:
        case 31:
        case 32:
        default:
          buffer.put((byte) (NEGFIXNUM | value));
      }
    } else {
      switch (Integer.numberOfLeadingZeros(value)) {
        case 0:
        case 1:
        case 2:
        case 3:
        case 4:
        case 5:
        case 6:
        case 7:
        case 8:
        case 9:
        case 10:
        case 11:
        case 12:
        case 13:
        case 14:
        case 15:
          buffer.put(UINT32);
          buffer.putInt(value);
          break;
        case 16:
        case 17:
        case 18:
        case 19:
        case 20:
        case 21:
        case 22:
        case 23:
          buffer.put(UINT16);
          buffer.putChar((char) value);
          break;
        case 24:
          buffer.put(UINT8);
          buffer.put((byte) value);
          break;
        case 25:
        case 26:
        case 27:
        case 28:
        case 29:
        case 30:
        case 31:
        case 32:
        default:
          buffer.put((byte) value);
      }
    }
  }

  @Override
  public void writeSignedInt(int value) {
    writeInt(value);
  }

  @Override
  public void writeLong(long value) {
    writeLongInternal(value, false);
  }

  @Override
  public void writeUnsignedLong(long value) {
    writeLongInternal(value, true);
  }

  public void writeLongInternal(long value, boolean forceUnsigned) {
    if (value < 0 && !forceUnsigned) {
      switch (Long.numberOfLeadingZeros(~value)) {
        case 0:
        case 1:
        case 2:
        case 3:
        case 4:
        case 5:
        case 6:
        case 7:
        case 8:
        case 9:
        case 10:
        case 11:
        case 12:
        case 13:
        case 14:
        case 15:
        case 16:
        case 17:
        case 18:
        case 19:
        case 20:
        case 21:
        case 22:
        case 23:
        case 24:
        case 25:
        case 26:
        case 27:
        case 28:
        case 29:
        case 30:
        case 31:
        case 32:
          buffer.put(INT64);
          buffer.putLong(value);
          break;
        case 33:
        case 34:
        case 35:
        case 36:
        case 37:
        case 38:
        case 39:
        case 40:
        case 41:
        case 42:
        case 43:
        case 44:
        case 45:
        case 46:
        case 47:
        case 48:
          buffer.put(INT32);
          buffer.putInt((int) value);
          break;
        case 49:
        case 50:
        case 51:
        case 52:
        case 53:
        case 54:
        case 55:
        case 56:
          buffer.put(INT16);
          buffer.putChar((char) value);
          break;
        case 57:
        case 58:
          buffer.put(INT8);
          buffer.put((byte) value);
          break;
        case 59:
        case 60:
        case 61:
        case 62:
        case 63:
        case 64:
        default:
          buffer.put((byte) (NEGFIXNUM | value));
      }
    } else {
      switch (Long.numberOfLeadingZeros(value)) {
        case 0:
        case 1:
        case 2:
        case 3:
        case 4:
        case 5:
        case 6:
        case 7:
        case 8:
        case 9:
        case 10:
        case 11:
        case 12:
        case 13:
        case 14:
        case 15:
        case 16:
        case 17:
        case 18:
        case 19:
        case 20:
        case 21:
        case 22:
        case 23:
        case 24:
        case 25:
        case 26:
        case 27:
        case 28:
        case 29:
        case 30:
        case 31:
          buffer.put(UINT64);
          buffer.putLong(value);
          break;
        case 32:
        case 33:
        case 34:
        case 35:
        case 36:
        case 37:
        case 38:
        case 39:
        case 40:
        case 41:
        case 42:
        case 43:
        case 44:
        case 45:
        case 46:
        case 47:
          buffer.put(UINT32);
          buffer.putInt((int) value);
          break;
        case 48:
        case 49:
        case 50:
        case 51:
        case 52:
        case 53:
        case 54:
        case 55:
          buffer.put(UINT16);
          buffer.putChar((char) value);
          break;
        case 56:
          buffer.put(UINT8);
          buffer.put((byte) value);
          break;
        case 57:
        case 59:
        case 60:
        case 61:
        case 62:
        case 63:
        case 64:
        default:
          buffer.put((byte) value);
      }
    }
  }

  @Override
  public void writeSignedLong(long value) {
    writeLong(value);
  }

  @Override
  public void writeFloat(float value) {
    // The datadog agent fails to decode FLOAT32 even though the code in read_bytes.go looks like
    // it will try to decode FLOAT32 just fine. Even if there will be a fix in a future datadog
    // agent release, we need to be backwards compatible here by sending it as a FLOAT64 instead.
    buffer.put(FLOAT64);
    buffer.putDouble(value);
  }

  @Override
  public void writeDouble(double value) {
    buffer.put(FLOAT64);
    buffer.putDouble(value);
  }

  @Override
  public void startMap(int elementCount) {
    if (elementCount < 0x10) {
      buffer.put((byte) (FIXMAP | elementCount));
    } else if (elementCount < 0x10000) {
      buffer.put(MAP16);
      buffer.putShort((short) elementCount);
    } else {
      buffer.put(MAP32);
      buffer.putInt(elementCount);
    }
  }

  @Override
  public void startStruct(int elementCount) {
    startArray(elementCount);
  }

  @Override
  public void startArray(int elementCount) {
    if (elementCount < 0x10) {
      buffer.put((byte) (FIXARRAY | elementCount));
    } else if (elementCount < 0x10000) {
      buffer.put(ARRAY16);
      buffer.putShort((short) elementCount);
    } else {
      buffer.put(ARRAY32);
      buffer.putInt(elementCount);
    }
  }

  void writeStringHeader(int length) {
    if (length < 0x10) {
      buffer.put((byte) (FIXSTR | length));
    } else if (length < 0x100) {
      buffer.put(STR8);
      buffer.put((byte) length);
    } else if (length < 0x10000) {
      buffer.put(STR16);
      buffer.putShort((short) length);
    } else {
      buffer.put(STR32);
      buffer.putInt(length);
    }
  }

  void writeBinaryHeader(int length) {
    if (length < 0x100) {
      buffer.put(BIN8);
      buffer.put((byte) length);
    } else if (length < 0x10000) {
      buffer.put(BIN16);
      buffer.putShort((short) length);
    } else {
      buffer.put(BIN32);
      buffer.putInt(length);
    }
  }
}

package datadog.trace.core.serialization.msgpack;

import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.util.Map;

/** Not thread-safe (use one per thread). */
public class Packer implements Writable, MessageFormatter {

  private static final int UTF8_BUFFER_SIZE = 8;
  private static final int MAX_ARRAY_HEADER_SIZE = 5;

  // see https://github.com/msgpack/msgpack/blob/master/spec.md
  private static final byte NULL = (byte) 0xC0;

  private static final byte FALSE = (byte) 0xC2;
  private static final byte TRUE = (byte) 0xC3;

  private static final byte UINT8 = (byte) 0xCC;
  private static final byte UINT16 = (byte) 0xCD;
  private static final byte UINT32 = (byte) 0xCE;
  private static final byte UINT64 = (byte) 0xCF;

  private static final byte INT8 = (byte) 0xD0;
  private static final byte INT16 = (byte) 0xD1;
  private static final byte INT32 = (byte) 0xD2;
  private static final byte INT64 = (byte) 0xD3;

  private static final byte FLOAT32 = (byte) 0xCA;
  private static final byte FLOAT64 = (byte) 0xCB;

  private static final byte STR8 = (byte) 0xD9;
  private static final byte STR16 = (byte) 0xDA;
  private static final byte STR32 = (byte) 0xDB;

  private static final byte BIN8 = (byte) 0xC4;
  private static final byte BIN16 = (byte) 0xC5;
  private static final byte BIN32 = (byte) 0xC6;

  private static final byte ARRAY16 = (byte) 0xDC;
  private static final byte ARRAY32 = (byte) 0xDD;

  private static final byte MAP16 = (byte) 0xDE;
  private static final byte MAP32 = (byte) 0xDF;

  private static final int NEGFIXNUM = 0xE0;
  private static final int FIXSTR = 0xA0;
  private static final int FIXARRAY = 0x90;
  private static final int FIXMAP = 0x80;

  private final Codec codec;

  private final ByteBufferConsumer sink;
  private final ByteBuffer buffer;
  private int messageCount = 0;

  private final byte[] utf8Buffer = new byte[UTF8_BUFFER_SIZE * 4];

  public Packer(Codec codec, ByteBufferConsumer sink, ByteBuffer buffer) {
    this.codec = codec;
    this.sink = sink;
    this.buffer = buffer;
    this.buffer.position(MAX_ARRAY_HEADER_SIZE);
    buffer.mark();
  }

  public Packer(ByteBufferConsumer sink, ByteBuffer buffer) {
    this(Codec.INSTANCE, sink, buffer);
  }

  @Override
  public <T> void format(T message, Mapper<T> mapper) {
    try {
      mapper.map(message, this);
      buffer.mark();
      ++messageCount;
    } catch (BufferOverflowException e) {
      // go back to the last successfully written message
      buffer.reset();
      if (buffer.position() == MAX_ARRAY_HEADER_SIZE) {
        throw e;
      }
      flush();
      format(message, mapper);
    }
  }

  @Override
  public void flush() {
    buffer.flip();
    int pos = 0;
    if (messageCount < 0x10) {
      pos = 4;
    } else if (messageCount < 0x10000) {
      pos = 2;
    }
    buffer.position(pos);
    writeArrayHeader(messageCount);
    buffer.position(pos);
    sink.accept(messageCount, buffer.slice());
    buffer.position(MAX_ARRAY_HEADER_SIZE);
    buffer.limit(buffer.capacity());
    messageCount = 0;
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
  @SuppressWarnings({"rawtypes", "unchecked"})
  public void writeObject(Object value, EncodingCache encodingCache) {
    if (null == value) {
      writeNull();
    } else {
      Writer writer = codec.get(value.getClass());
      writer.write(value, this, encodingCache);
    }
  }

  @Override
  public void writeMap(
      Map<? extends CharSequence, ? extends Object> map, EncodingCache encodingCache) {
    writeMapHeader(map.size());
    for (Map.Entry<? extends CharSequence, ? extends Object> entry : map.entrySet()) {
      writeString(entry.getKey(), encodingCache);
      writeObject(entry.getValue(), encodingCache);
    }
  }

  @Override
  public void writeString(CharSequence s, EncodingCache encodingCache) {
    if (null == s) {
      writeNull();
    } else {
      byte[] utf8 = encodingCache.encode(s);
      if (null == utf8) {
        if (s.length() < UTF8_BUFFER_SIZE) {
          utf8EncodeWithArray(s);
        } else {
          utf8Encode(s);
        }
      } else {
        writeUTF8(utf8, 0, utf8.length);
      }
    }
  }

  private void utf8EncodeWithArray(CharSequence s) {
    int mark = buffer.position();
    writeStringHeader(s.length());
    int actualLength = utf8EncodeViaArray(s, 0);
    if (actualLength > s.length()) {
      int lengthWritten = stringLength(s.length());
      int lengthRequired = stringLength(actualLength);
      if (lengthRequired != lengthWritten) {
        // could shift the string itself to the right but just do it again
        buffer.position(mark);
        writeStringHeader(actualLength);
        utf8EncodeViaArray(s, 0);
      } else { // just go back and fix it
        fixStringHeaderInPlace(mark, lengthRequired, actualLength);
      }
    }
  }

  private int utf8EncodeViaArray(CharSequence s, int in) {
    byte[] buffer = utf8Buffer;
    int out = 0;
    for (; in < s.length() && out < buffer.length; ++in) {
      char c = s.charAt(in);
      if (c < 0x80) {
        buffer[out++] = ((byte) c);
      } else if (c < 0x800) {
        buffer[out++] = ((byte) (0xC0 | (c >> 6)));
        buffer[out++] = ((byte) (0x80 | (c & 0x3F)));
      } else if (Character.isSurrogate(c)) {
        if (!Character.isHighSurrogate(c)) {
          buffer[out++] = ((byte) '?');
        } else if (++in == s.length()) {
          buffer[out++] = ((byte) '?');
        } else {
          char next = s.charAt(in);
          if (!Character.isLowSurrogate(next)) {
            buffer[out++] = ((byte) '?');
            buffer[out++] = (Character.isHighSurrogate(next) ? (byte) '?' : (byte) next);
          } else {
            int codePoint = Character.toCodePoint(c, next);
            buffer[out++] = ((byte) (0xF0 | (codePoint >> 18)));
            buffer[out++] = ((byte) (0x80 | ((codePoint >> 12) & 0x3F)));
            buffer[out++] = ((byte) (0x80 | ((codePoint >> 6) & 0x3F)));
            buffer[out++] = ((byte) (0x80 | (codePoint & 0x3F)));
          }
        }
      } else {
        buffer[out++] = (byte) (0xE0 | c >> 12);
        buffer[out++] = (byte) (0x80 | c >> 6 & 0x3F);
        buffer[out++] = (byte) (0x80 | c & 0x3F);
      }
    }
    this.buffer.put(buffer, 0, out);
    if (in < s.length()) {
      return out + utf8EncodeViaArray(s, in);
    }
    return out;
  }

  private void utf8Encode(CharSequence s) {
    int mark = buffer.position();
    writeStringHeader(s.length());
    int actualLength = utf8EncodeSWAR(s);
    if (actualLength > s.length()) {
      int lengthWritten = stringLength(s.length());
      int lengthRequired = stringLength(actualLength);
      if (lengthRequired != lengthWritten) {
        // could shift the string itself to the right but just do it again
        buffer.position(mark);
        writeStringHeader(actualLength);
        utf8EncodeSWAR(s);
      } else { // just go back and fix it
        fixStringHeaderInPlace(mark, lengthRequired, actualLength);
      }
    }
  }

  private int utf8EncodeSWAR(CharSequence s) {
    int i = 0;
    int written = 0;
    long word;
    while (i + 7 < s.length()) {
      // bounds check elimination:
      // latin 1 text will never use more than 7 bits per character,
      // we can detect non latin 1 and revert to a slow path by
      // merging the chars and checking every 8th bit is empty
      word = s.charAt(i);
      word = (word << 8 | s.charAt(i + 1));
      word = (word << 8 | s.charAt(i + 2));
      word = (word << 8 | s.charAt(i + 3));
      word = (word << 8 | s.charAt(i + 4));
      word = (word << 8 | s.charAt(i + 5));
      word = (word << 8 | s.charAt(i + 6));
      word = (word << 8 | s.charAt(i + 7));
      if ((word & 0x7F7F7F7F7F7F7F7FL) == word) {
        buffer.putLong(word);
        written += 8;
        i += 8;
      } else { // redo some work, stupid but careful
        int j = i;
        for (; j < i + 8; ++j) {
          char c = s.charAt(j);
          if (c < 0x80) {
            buffer.put((byte) c);
            written++;
          } else if (c < 0x800) {
            buffer.putChar((char) (((0xC0 | (c >> 6)) << 8) | (0x80 | (c & 0x3F))));
            written += 2;
          } else if (Character.isSurrogate(c)) {
            if (!Character.isHighSurrogate(c)) {
              buffer.put((byte) '?');
              written++;
            } else if (++j == s.length()) {
              buffer.put((byte) '?');
              written++;
            } else {
              char next = s.charAt(j);
              if (!Character.isLowSurrogate(next)) {
                buffer.put((byte) '?');
                buffer.put(Character.isHighSurrogate(next) ? (byte) '?' : (byte) next);
                written += 2;
              } else {
                int codePoint = Character.toCodePoint(c, next);
                buffer.putInt(
                    ((0xF0 | (codePoint >> 18)) << 24)
                        | ((0x80 | ((codePoint >> 12) & 0x3F)) << 16)
                        | ((0x80 | ((codePoint >> 6) & 0x3F)) << 8)
                        | ((0x80 | (codePoint & 0x3F))));
                written += 4;
              }
            }
          } else {
            buffer.put((byte) (0xE0 | c >> 12));
            buffer.put((byte) (0x80 | c >> 6 & 0x3F));
            buffer.put((byte) (0x80 | c & 0x3F));
            written += 3;
          }
        }
        i = j;
      }
    }
    if (i < s.length()) {
      written += utf8EncodeViaArray(s, i);
    }
    return written;
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
  public void writeLong(long value) {
    if (value < 0) {
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
  public void writeFloat(float value) {
    buffer.put(FLOAT32);
    buffer.putFloat(value);
  }

  @Override
  public void writeDouble(double value) {
    buffer.put(FLOAT64);
    buffer.putDouble(value);
  }

  @Override
  public void startMap(int elementCount) {
    writeMapHeader(elementCount);
  }

  @Override
  public void startArray(int elementCount) {
    writeArrayHeader(elementCount);
  }

  void writeStringHeader(int length) {
    if (length < 0x10) {
      buffer.put((byte) (FIXSTR | length));
    } else if (length < 0x100) {
      buffer.put(STR8);
      buffer.put((byte) length);
    } else if (length < 0x10000) {
      buffer.put(STR16);
      buffer.putChar((char) length);
    } else {
      buffer.put(STR32);
      buffer.putInt(length);
    }
  }

  void writeArrayHeader(int length) {
    if (length < 0x10) {
      buffer.put((byte) (FIXARRAY | length));
    } else if (length < 0x10000) {
      buffer.put(ARRAY16);
      buffer.putChar((char) length);
    } else {
      buffer.put(ARRAY32);
      buffer.putInt(length);
    }
  }

  void writeMapHeader(int length) {
    if (length < 0x10) {
      buffer.put((byte) (FIXMAP | length));
    } else if (length < 0x10000) {
      buffer.put(MAP16);
      buffer.putChar((char) length);
    } else {
      buffer.put(MAP32);
      buffer.putInt(length);
    }
  }

  void writeBinaryHeader(int length) {
    if (length < 0x100) {
      buffer.put(BIN8);
      buffer.put((byte) length);
    } else if (length < 0x10000) {
      buffer.put(BIN16);
      buffer.putChar((char) length);
    } else {
      buffer.put(BIN32);
      buffer.putInt(length);
    }
  }

  private static int stringLength(int length) {
    if (length < 0x10) {
      return FIXSTR;
    } else if (length < 0x100) {
      return STR8;
    } else if (length < 0x10000) {
      return STR16;
    } else {
      return STR32;
    }
  }

  private void fixStringHeaderInPlace(int mark, int lengthType, int actualLength) {
    switch (lengthType) {
      case FIXSTR:
        buffer.put(mark, (byte) (FIXSTR | actualLength));
        break;
      case STR8:
        buffer.put(mark + 1, (byte) (actualLength));
        break;
      case STR16:
        buffer.putChar(mark + 1, (char) (actualLength));
        break;
      case STR32:
        buffer.putInt(mark + 1, (actualLength));
        break;
    }
  }
}

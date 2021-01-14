package datadog.trace.core.serialization.msgpack;

import static datadog.trace.api.Platform.isJavaVersionAtLeast;
import static java.nio.charset.StandardCharsets.UTF_8;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.core.serialization.ByteBufferConsumer;
import datadog.trace.core.serialization.Codec;
import datadog.trace.core.serialization.EncodingCache;
import datadog.trace.core.serialization.Mapper;
import datadog.trace.core.serialization.ValueWriter;
import datadog.trace.core.serialization.WritableFormatter;

import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.util.EnumSet;
import java.util.Map;

/** Not thread-safe (use one per thread). */
public class MsgPackWriter implements WritableFormatter {

  private static final int MAX_ARRAY_HEADER_SIZE = 5;

  private static final boolean IS_JVM_9_OR_LATER = isJavaVersionAtLeast(9);

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

  public enum Feature {
    MANUAL_RESET,
    RESIZEABLE,
    SINGLE_MESSAGE
  }

  private final Codec codec;

  private final ByteBufferConsumer sink;
  private final int maxArrayHeaderSize;

  private final boolean resizeable;
  private final boolean manualReset;
  private final boolean writeArray;

  protected ByteBuffer buffer;
  protected int messageCount = 0;

  protected MsgPackWriter(
    Codec codec,
    ByteBufferConsumer sink,
    ByteBuffer buffer,
    EnumSet<MsgPackWriter.Feature> features,
    int maxArrayHeaderSize) {
    this.codec = codec;
    this.sink = sink;
    this.buffer = buffer;
    this.manualReset = features.contains(MsgPackWriter.Feature.MANUAL_RESET);
    this.resizeable = features.contains(MsgPackWriter.Feature.RESIZEABLE);
    this.writeArray = !features.contains(MsgPackWriter.Feature.SINGLE_MESSAGE);
    this.maxArrayHeaderSize = maxArrayHeaderSize;
    initBuffer();
  }

  public MsgPackWriter(
      Codec codec, ByteBufferConsumer sink, ByteBuffer buffer, boolean manualReset) {
    this(
        codec,
        sink,
        buffer,
        manualReset ? EnumSet.of(Feature.MANUAL_RESET) : EnumSet.noneOf(Feature.class),
        5);
  }

  public MsgPackWriter(Codec codec, ByteBufferConsumer sink, ByteBuffer buffer) {
    this(codec, sink, buffer, false);
  }

  public MsgPackWriter(ByteBufferConsumer sink, ByteBuffer buffer) {
    this(Codec.INSTANCE, sink, buffer);
  }

  public MsgPackWriter(
      ByteBufferConsumer sink, ByteBuffer buffer, EnumSet<MsgPackWriter.Feature> features) {
    this(Codec.INSTANCE, sink, buffer, features, 5);
  }

  public MsgPackWriter(ByteBufferConsumer sink, ByteBuffer buffer, boolean manualReset) {
    this(Codec.INSTANCE, sink, buffer, manualReset);
  }

  protected void initBuffer() {
    this.buffer.position(MAX_ARRAY_HEADER_SIZE);
    buffer.mark();
  }

  public int messageCount() {
    return messageCount;
  }

  protected void mark() {
    buffer.mark();
    ++messageCount;
  }

  @Override
  public void flush() {
    buffer.flip();
    writeHeader(writeArray);
    sink.accept(messageCount, buffer.slice());
    if (!manualReset) {
      reset();
    }
  }

  @Override
  public <T> boolean format(T message, Mapper<T> mapper) {
    try {
      mapper.map(message, this);
      // What happens here is when an entire message is put into the buffer
      // (i.e. it's possible without overflow, we go to the next line, not
      // the catch block) the buffer gets marked (i.e. we take a snapshot
      // of the buffer's current position, where the next message would
      // start). When there is overflow, the buffer is reset, so the
      // position is restored to that snapshot and the serialisation work
      // of `message` is lost.
      //
      // Now there are two cases:
      //
      // 1. That snapshot is the start of the buffer, so the object being mapped
      //    is larger than we have space for. This is unrecoverable, and we're
      //    relying on this not being possible. However, we have hard limits on
      //    what we *can* send to the agent, so all traces have an implicit size
      //    limit. So we need to select a buffer large enough for at least one
      //    trace (and hopefully many).
      // 2. Otherwise, the snapshot position is where the last successfully written
      //    message ended. If we flip the buffer, it can be dispatched to the sink.
      //    After it's been dispatched, we can write the message which caused the
      //    overflow into the now reset buffer, doing the serialisation work again.
      mark();
      return true;
    } catch (BufferOverflowException e) {
      // go back to the last successfully written message
      buffer.reset();
      if (resizeable) {
        this.buffer = resize(this.buffer);
        return format(message, mapper);
      } else if (!manualReset) {
        if (buffer.position() == maxArrayHeaderSize) {
          throw e;
        }
        flush();
        return format(message, mapper);
      } else {
        return false;
      }
    }
  }

  // NOTE - implementations pulled up to this level should
  // not write directly to the buffer

  @Override
  public void writeMap(
    Map<? extends CharSequence, ? extends Object> map, EncodingCache encodingCache) {
    startMap(map.size());
    for (Map.Entry<? extends CharSequence, ? extends Object> entry : map.entrySet()) {
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

  private static ByteBuffer resize(ByteBuffer oldBuffer) {
    ByteBuffer newBuffer = ByteBuffer.allocate(oldBuffer.capacity() * 2);
    oldBuffer.flip();
    newBuffer.put(oldBuffer);
    return newBuffer;
  }

  @Override
  public void reset() {
    initBuffer();
    buffer.limit(buffer.capacity());
    messageCount = 0;
  }

  protected void writeHeader(boolean writeArray) {
    if (writeArray) {
      int pos = headerPosition();
      buffer.position(pos);
      startArray(messageCount);
      buffer.position(pos);
    } else {
      buffer.position(MAX_ARRAY_HEADER_SIZE);
    }
  }

  private int headerPosition() {
    if (messageCount < 0x10) {
      return 4;
    } else if (messageCount < 0x10000) {
      return 2;
    }
    return 0;
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
      writeUTF8String(s);
    }
  }

  private void writeUTF8String(CharSequence s) {
    int mark = buffer.position();
    writeStringHeader(s.length());
    int actualLength = utf8Encode(s);
    if (actualLength > s.length()) {
      int lengthWritten = stringLength(s.length());
      int lengthRequired = stringLength(actualLength);
      if (lengthRequired != lengthWritten) {
        // could shift the string itself to the right but just do it again
        buffer.position(mark);
        writeStringHeader(actualLength);
        utf8Encode(s);
      } else { // just go back and fix it
        fixStringHeaderInPlace(mark, lengthRequired, actualLength);
      }
    }
  }

  private int utf8Encode(CharSequence s) {
    if (IS_JVM_9_OR_LATER && s.length() < 64 && s instanceof String) {
      // this allocates less with JDK9+ and is a lot faster than
      // doing it yourself (avoids lots of bounds checks).
      // Using UTF8ByteString wherever possible should make this rare anyway.
      byte[] utf8 = ((String) s).getBytes(UTF_8);
      buffer.put(utf8);
      return utf8.length;
    }
    return allocationFreeUTF8Encode(s);
  }

  private int allocationFreeUTF8Encode(CharSequence s) {
    int written = 0;
    for (int i = 0; i < s.length(); ++i) {
      char c = s.charAt(i);
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
        } else if (++i == s.length()) {
          buffer.put((byte) '?');
          written++;
        } else {
          char next = s.charAt(i);
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
        buffer.putChar((char) (((0xE0 | c >> 12) << 8) | (0x80 | c >> 6 & 0x3F)));
        buffer.put((byte) (0x80 | c & 0x3F));
        written += 3;
      }
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
  public void writeUTF8(UTF8BytesString string) {
    writeStringHeader(string.encodedLength());
    string.transferTo(buffer);
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
  public void writeSignedLong(long value) {
    writeLong(value);
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
  public void startStruct(int elementCount) {
    startArray(elementCount);
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

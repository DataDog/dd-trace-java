package datadog.trace.core.serialization.protobuf;

import static java.nio.charset.StandardCharsets.UTF_8;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.core.serialization.ByteBufferConsumer;
import datadog.trace.core.serialization.Codec;
import datadog.trace.core.serialization.EncodingCache;
import datadog.trace.core.serialization.Writable;
import datadog.trace.core.serialization.WritableFormatter;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.Map;

/**
 * Gives low level access to the protobuf format. Requires writers to write fields in the correct
 * order (which is also beneficial for readers hoping to skip fields, allowing the reader to pass
 * over the buffer once).
 */
public class ProtobufWriter extends WritableFormatter {

  // any integer type including booleans
  private static final int VARINT = 0;
  // doubles
  private static final int FIXED_64 = 1;
  // strings, binary, arrays (i.e. repeated fields), embedded structs (i.e. messages)
  private static final int LENGTH_DELIMITED = 2;
  // floats
  private static final int FIXED_32 = 5;

  /**
   * Protobuf models embedded structures in the same way it does strings: as a sequence of bytes
   * prefixed by a varint encoded length. This means that protobuf can't be written using streaming
   * techniques, because the length in bytes of an embedded structure can't be known until it has
   * been serialised, and calculating the length is almost as expensive as actually doing the
   * serialisation. One option (which rules out streaming entirely) is to write messages backwards.
   * The approach taken is to serialise embedded structures in the top level of a stack of buffers,
   * and when the top of the stack is popped, the contents of the popped stack frame is written as a
   * binary string into the top of the parent stack frame.
   */
  private class Context implements Writable {
    private final ByteBuffer buffer;
    private int elementCount = Integer.MAX_VALUE;
    private int fieldNumber = 1;
    private int position = 0;
    private boolean inArray = false;

    private Context(ByteBuffer buffer) {
      this.buffer = buffer;
    }

    private Context() {
      // 512 KB
      this(ByteBuffer.allocate(1 << 19));
    }

    void reset() {
      buffer.position(0);
      buffer.limit(buffer.capacity());
      this.fieldNumber = 1;
      this.position = 0;
      this.elementCount = Integer.MAX_VALUE;
    }

    void transferTo(Context target) {
      buffer.flip();
      target.writeBinary(buffer);
    }

    @Override
    public void writeNull() {
      nextElement();
    }

    @Override
    public void writeBoolean(boolean value) {
      if (value) {
        if (!inArray) {
          writeTag(VARINT);
        }
        writeVarInt(1);
      }
      nextElement();
    }

    @Override
    public void writeObject(Object value, EncodingCache encodingCache) {
      ProtobufWriter.this.writeObject(value, encodingCache);
      nextElement();
    }

    @Override
    public void writeMap(Map<? extends CharSequence, ?> map, EncodingCache encodingCache) {
      if (!map.isEmpty()) {
        ProtobufWriter.this.writeMap(map, encodingCache);
      }
      nextElement();
    }

    @Override
    public void writeString(CharSequence s, EncodingCache encodingCache) {
      if (s instanceof UTF8BytesString) {
        writeUTF8((UTF8BytesString) s);
      } else {
        // TODO consider avoiding allocations here
        writeUTF8(String.valueOf(s).getBytes(UTF_8));
      }
    }

    @Override
    public void writeUTF8(byte[] string, int offset, int length) {
      if (length != 0) {
        writeLengthPrefix(length);
        buffer.put(string, offset, length);
      }
      nextElement();
    }

    @Override
    public void writeUTF8(byte[] string) {
      if (string.length != 0) {
        writeLengthPrefix(string.length);
        buffer.put(string);
      }
      nextElement();
    }

    @Override
    public void writeUTF8(UTF8BytesString string) {
      if (string.encodedLength() != 0) {
        writeLengthPrefix(string.encodedLength());
        string.transferTo(buffer);
      }
      nextElement();
    }

    @Override
    public void writeBinary(byte[] binary, int offset, int length) {
      if (length != 0) {
        writeLengthPrefix(length);
        buffer.put(binary, offset, length);
      }
      nextElement();
    }

    @Override
    public void writeBinary(ByteBuffer buffer) {
      if (buffer.hasRemaining()) {
        writeLengthPrefix(buffer.remaining());
        this.buffer.put(buffer);
      }
      nextElement();
    }

    @Override
    public void startMap(int elementCount) {
      if (elementCount != 0) {
        ProtobufWriter.this.startMap(elementCount);
      }
    }

    @Override
    public void startStruct(int elementCount) {
      if (elementCount != 0) {
        ProtobufWriter.this.startStruct(elementCount);
      }
    }

    @Override
    public void startArray(int elementCount) {
      if (elementCount != 0) {
        ProtobufWriter.this.startArray(elementCount);
      }
    }

    @Override
    public void writeInt(int value) {
      if (value != 0) {
        if (!inArray) {
          writeTag(VARINT);
        }
        writeVarInt(value);
      }
      nextElement();
    }

    @Override
    public void writeSignedInt(int value) {
      writeInt((value << 1) ^ (value >> 31));
    }

    @Override
    public void writeLong(long value) {
      if (value != 0) {
        if (!inArray) {
          writeTag(VARINT);
        }
        writeVarInt(value);
      }
      nextElement();
    }

    @Override
    public void writeSignedLong(long value) {
      writeLong((value << 1) ^ (value >> 63));
    }

    @Override
    public void writeFloat(float value) {
      if (value != 0f) {
        if (!inArray) {
          writeTag(FIXED_32);
        }
        buffer.putFloat(value);
      }
      nextElement();
    }

    @Override
    public void writeDouble(double value) {
      if (value != 0d) {
        if (!inArray) {
          writeTag(FIXED_64);
        }
        buffer.putDouble(value);
      }
      nextElement();
    }

    private void writeLengthPrefix(int length) {
      writeTag(LENGTH_DELIMITED);
      writeVarInt(length);
    }

    private void nextElement() {
      if (!inArray) {
        ++fieldNumber;
      }
      ++position;
      checkElementCountInvariant();
    }

    private void checkElementCountInvariant() {
      if (position == elementCount) {
        ProtobufWriter.this.leaveContext();
      }
    }

    void writeTag(int wireType) {
      writeVarInt((fieldNumber << 3) | wireType);
    }

    private void writeVarInt(int value) {
      int length = varIntLength(value);
      for (int i = 0; i < length; ++i) {
        buffer.put((byte) ((value & 0x7F) | 0x80));
        value >>>= 7;
      }
      buffer.put((byte) value);
    }

    private void writeVarInt(long value) {
      int length = varIntLength(value);
      for (int i = 0; i < length; ++i) {
        buffer.put((byte) ((value & 0x7F) | 0x80));
        value >>>= 7;
      }
      buffer.put((byte) value);
    }

    private int varIntLength(int value) {
      return (31 - Integer.numberOfLeadingZeros(value)) / 7;
    }

    private int varIntLength(long value) {
      return (63 - Long.numberOfLeadingZeros(value)) / 7;
    }
  }

  private final Deque<Context> pool = new ArrayDeque<>();
  private final Deque<Context> stack = new ArrayDeque<>();
  private final Context root;
  private Context active;

  public ProtobufWriter(
      Codec codec, ByteBufferConsumer sink, ByteBuffer buffer, boolean manualReset) {
    super(
        codec,
        sink,
        buffer,
        manualReset ? EnumSet.of(Feature.MANUAL_RESET) : EnumSet.noneOf(Feature.class),
        0);
    this.root = new Context(buffer);
    this.active = root;
    stack.push(active);
    // pre-allocate for three levels of nesting
    pool.push(new Context());
    pool.push(new Context());
    pool.push(new Context());
  }

  public ProtobufWriter(Codec codec, ByteBufferConsumer sink, ByteBuffer buffer) {
    this(codec, sink, buffer, false);
  }

  public ProtobufWriter(ByteBufferConsumer sink, ByteBuffer buffer) {
    this(Codec.INSTANCE, sink, buffer);
  }

  public ProtobufWriter(ByteBufferConsumer sink, ByteBuffer buffer, boolean manualReset) {
    this(Codec.INSTANCE, sink, buffer, manualReset);
  }

  @Override
  public void reset() {
    initBuffer();
    buffer.limit(buffer.capacity());
    buffer.position(0);
    messageCount = 0;
    // should unwind anyway, but just in case
    resetStack();
  }

  private void resetStack() {
    if (active != root) {
      recycle(active);
    }
    for (Context context : stack) {
      if (context != root) {
        recycle(context);
      }
    }
    active = root;
  }

  private void recycle(Context context) {
    context.reset();
    pool.addLast(context);
  }

  private void enterContext(int elementCount, boolean array) {
    Context context = pool.isEmpty() ? new Context() : pool.removeFirst();
    context.elementCount = elementCount;
    context.inArray = array;
    stack.addFirst(context);
    this.active = context;
  }

  private void leaveContext() {
    // don't need to do any stack management
    // if we're at the top level writing flat
    // messages
    if (active != root) {
      Context context = stack.removeFirst();
      this.active = stack.peek();
      assert active != null;
      context.transferTo(active);
      recycle(context);
    }
  }

  @Override
  protected void writeHeader(boolean writeArray) {
    buffer.position(0);
  }

  @Override
  public void startMap(int elementCount) {
    // keys and values
    enterContext(elementCount * 2, false);
  }

  @Override
  public void startStruct(int elementCount) {
    enterContext(elementCount, false);
  }

  @Override
  public void startArray(int elementCount) {
    enterContext(elementCount, true);
  }

  @Override
  public void writeNull() {
    active.writeNull();
  }

  @Override
  public void writeBoolean(boolean value) {
    active.writeBoolean(value);
  }

  @Override
  public void writeString(CharSequence s, EncodingCache encodingCache) {
    active.writeString(s, encodingCache);
  }

  @Override
  public void writeUTF8(byte[] string, int offset, int length) {
    active.writeUTF8(string, offset, length);
  }

  @Override
  public void writeUTF8(byte[] string) {
    active.writeUTF8(string);
  }

  @Override
  public void writeUTF8(UTF8BytesString string) {
    active.writeUTF8(string);
  }

  @Override
  public void writeBinary(byte[] binary, int offset, int length) {
    active.writeBinary(binary, offset, length);
  }

  @Override
  public void writeBinary(ByteBuffer buffer) {
    active.writeBinary(buffer);
  }

  @Override
  public void writeInt(int value) {
    active.writeInt(value);
  }

  @Override
  public void writeSignedInt(int value) {
    active.writeSignedInt(value);
  }

  @Override
  public void writeLong(long value) {
    active.writeLong(value);
  }

  @Override
  public void writeSignedLong(long value) {
    active.writeSignedLong(value);
  }

  @Override
  public void writeFloat(float value) {
    active.writeFloat(value);
  }

  @Override
  public void writeDouble(double value) {
    active.writeDouble(value);
  }
}

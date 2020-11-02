package datadog.trace.core.serialization.protobuf;

import static java.nio.charset.StandardCharsets.UTF_8;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.core.serialization.ByteBufferConsumer;
import datadog.trace.core.serialization.Codec;
import datadog.trace.core.serialization.EncodingCache;
import datadog.trace.core.serialization.WritableFormatter;
import java.nio.ByteBuffer;

public class ProtobufWriter extends WritableFormatter {

  private static final int VARINT = 0;
  private static final int FIXED_64 = 1;
  private static final int LENGTH_DELIMITED = 2;
  private static final int FIXED_32 = 5;

  private static final int MAX_ARRAY_HEADER_SIZE = 5;

  private int fieldNumber = 0;

  public ProtobufWriter(
      Codec codec, ByteBufferConsumer sink, ByteBuffer buffer, boolean manualReset) {
    super(codec, sink, buffer, manualReset, MAX_ARRAY_HEADER_SIZE);
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
  protected void mark() {
    super.mark();
    fieldNumber = 0;
  }

  @Override
  public void reset() {
    super.reset();
    fieldNumber = 0;
  }

  @Override
  protected int headerPosition() {
    return MAX_ARRAY_HEADER_SIZE - varIntLength(messageCount) - 1;
  }

  @Override
  public void writeNull() {
    ++fieldNumber;
  }

  @Override
  public void writeBoolean(boolean value) {
    writeTag(VARINT);
    writeVarInt(value ? 1 : 0);
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
    startArray(length - offset);
    buffer.put(string, offset, length);
  }

  @Override
  public void writeUTF8(byte[] string) {
    startArray(string.length);
    buffer.put(string);
  }

  @Override
  public void writeUTF8(UTF8BytesString string) {
    startArray(string.length());
    string.transferTo(buffer);
  }

  @Override
  public void writeBinary(byte[] binary, int offset, int length) {
    startArray(length - offset);
    buffer.put(binary, offset, length);
  }

  @Override
  public void startMap(int elementCount) {
    startArray(elementCount);
  }

  @Override
  public void startArray(int elementCount) {
    writeTag(LENGTH_DELIMITED);
    writeVarInt(elementCount);
  }

  @Override
  public void writeBinary(ByteBuffer buffer) {
    startArray(buffer.remaining());
    this.buffer.put(buffer);
  }

  @Override
  public void writeInt(int value) {
    writeTag(VARINT);
    writeVarInt((value << 1) ^ (value >>> 31));
  }

  @Override
  public void writeLong(long value) {
    writeTag(VARINT);
    writeVarInt((value << 1) ^ (value >>> 63));
  }

  @Override
  public void writeFloat(float value) {
    writeTag(FIXED_32);
    buffer.putFloat(value);
  }

  @Override
  public void writeDouble(double value) {
    writeTag(FIXED_64);
    buffer.putDouble(value);
  }

  void writeTag(int wireType) {
    buffer.put((byte) ((fieldNumber << 3) | wireType));
  }

  void writeVarInt(int value) {
    int length = varIntLength(value);
    for (int i = 0; i < length - 1; ++i) {
      buffer.put((byte) ((value & 0x7F) | 0x80));
      value >>>= 7;
    }
    buffer.put((byte) value);
  }

  void writeVarInt(long value) {
    int length = varIntLength(value);
    for (int i = 0; i < length - 1; ++i) {
      buffer.put((byte) ((value & 0x7F) | 0x80));
      value >>>= 7;
    }
    buffer.put((byte) value);
  }

  private int varIntLength(int value) {
    return (Integer.numberOfTrailingZeros(value) / 7) + 1;
  }

  private int varIntLength(long value) {
    return (Long.numberOfTrailingZeros(value) / 7) + 1;
  }
}

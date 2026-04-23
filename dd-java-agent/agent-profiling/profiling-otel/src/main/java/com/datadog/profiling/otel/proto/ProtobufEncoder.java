package com.datadog.profiling.otel.proto;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Low-level protobuf encoder without external dependencies. Implements the protobuf wire format for
 * encoding messages.
 */
public final class ProtobufEncoder {

  // Wire types
  public static final int WIRETYPE_VARINT = 0;
  public static final int WIRETYPE_FIXED64 = 1;
  public static final int WIRETYPE_LENGTH_DELIMITED = 2;
  public static final int WIRETYPE_FIXED32 = 5;

  private byte[] buf;
  private int size;
  // Lazily initialized child encoder, reused across writeNestedMessage / writePackedVarintField
  // calls to avoid per-call allocation. Nesting is always sequential, so one child per depth level
  // (forming a chain) is safe.
  private ProtobufEncoder child;

  public ProtobufEncoder() {
    this.buf = new byte[4096];
  }

  public ProtobufEncoder(int initialCapacity) {
    this.buf = new byte[initialCapacity];
  }

  /** Resets the encoder for reuse. */
  public void reset() {
    size = 0;
  }

  private void resize(int needed) {
    buf = Arrays.copyOf(buf, Math.max(buf.length * 2, size + needed));
  }

  public void writeTag(int fieldNumber, int wireType) {
    writeVarint((fieldNumber << 3) | wireType);
  }

  public void writeVarint(long value) {
    if (size + 10 > buf.length) resize(10);
    while ((value & ~0x7FL) != 0) {
      buf[size++] = (byte) ((value & 0x7F) | 0x80);
      value >>>= 7;
    }
    buf[size++] = (byte) value;
  }

  public void writeSignedVarint(long value) {
    writeVarint((value << 1) ^ (value >> 63));
  }

  public void writeFixed64(long value) {
    if (size + 8 > buf.length) resize(8);
    buf[size    ] = (byte)  (value        & 0xFF);
    buf[size + 1] = (byte) ((value >>  8) & 0xFF);
    buf[size + 2] = (byte) ((value >> 16) & 0xFF);
    buf[size + 3] = (byte) ((value >> 24) & 0xFF);
    buf[size + 4] = (byte) ((value >> 32) & 0xFF);
    buf[size + 5] = (byte) ((value >> 40) & 0xFF);
    buf[size + 6] = (byte) ((value >> 48) & 0xFF);
    buf[size + 7] = (byte) ((value >> 56) & 0xFF);
    size += 8;
  }

  public void writeFixed32(int value) {
    if (size + 4 > buf.length) resize(4);
    buf[size    ] = (byte)  (value        & 0xFF);
    buf[size + 1] = (byte) ((value >>  8) & 0xFF);
    buf[size + 2] = (byte) ((value >> 16) & 0xFF);
    buf[size + 3] = (byte) ((value >> 24) & 0xFF);
    size += 4;
  }

  public void writeBytes(byte[] bytes) {
    writeVarint(bytes.length);
    if (size + bytes.length > buf.length) resize(bytes.length);
    System.arraycopy(bytes, 0, buf, size, bytes.length);
    size += bytes.length;
  }

  public void writeString(String value) {
    if (value == null || value.isEmpty()) {
      writeVarint(0);
      return;
    }
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    writeBytes(bytes);
  }

  /**
   * Writes a nested message as a length-delimited field. The child encoder is reused to avoid
   * allocation; content is copied directly into this encoder's buffer without an intermediate
   * {@code toByteArray()} copy.
   *
   * <p>Empty messages are always written (tag + zero length): required for OTLP dictionary tables
   * where index 0 is the null/unset sentinel.
   */
  public void writeNestedMessage(int fieldNumber, MessageWriter writer) {
    if (child == null) {
      child = new ProtobufEncoder();
    } else {
      child.reset();
    }
    writer.write(child);

    writeTag(fieldNumber, WIRETYPE_LENGTH_DELIMITED);
    writeVarint(child.size);
    if (child.size > 0) {
      if (size + child.size > buf.length) resize(child.size);
      System.arraycopy(child.buf, 0, buf, size, child.size);
      size += child.size;
    }
  }

  public void writeVarintField(int fieldNumber, long value) {
    if (value != 0) {
      writeTag(fieldNumber, WIRETYPE_VARINT);
      writeVarint(value);
    }
  }

  public void writeSignedVarintField(int fieldNumber, long value) {
    if (value != 0) {
      writeTag(fieldNumber, WIRETYPE_VARINT);
      writeSignedVarint(value);
    }
  }

  public void writeFixed64Field(int fieldNumber, long value) {
    if (value != 0) {
      writeTag(fieldNumber, WIRETYPE_FIXED64);
      writeFixed64(value);
    }
  }

  public void writeFixed32Field(int fieldNumber, int value) {
    if (value != 0) {
      writeTag(fieldNumber, WIRETYPE_FIXED32);
      writeFixed32(value);
    }
  }

  public void writeStringField(int fieldNumber, String value) {
    if (value != null && !value.isEmpty()) {
      writeTag(fieldNumber, WIRETYPE_LENGTH_DELIMITED);
      writeString(value);
    }
  }

  public void writeBytesField(int fieldNumber, byte[] value) {
    if (value != null && value.length > 0) {
      writeTag(fieldNumber, WIRETYPE_LENGTH_DELIMITED);
      writeBytes(value);
    }
  }

  public void writeBytesField(int fieldNumber, InputStream inputStream, long length)
      throws IOException {
    if (inputStream == null || length == 0) {
      return;
    }

    writeTag(fieldNumber, WIRETYPE_LENGTH_DELIMITED);
    writeVarint(length);

    byte[] chunk = new byte[8192];
    long remaining = length;
    try {
      while (remaining > 0) {
        int toRead = (int) Math.min(chunk.length, remaining);
        int read = inputStream.read(chunk, 0, toRead);
        if (read < 0) {
          throw new IOException("Unexpected end of stream");
        }
        if (size + read > buf.length) resize(read);
        System.arraycopy(chunk, 0, buf, size, read);
        size += read;
        remaining -= read;
      }
    } finally {
      inputStream.close();
    }
  }

  public void writeBoolField(int fieldNumber, boolean value) {
    if (value) {
      writeTag(fieldNumber, WIRETYPE_VARINT);
      writeVarint(1);
    }
  }

  public void writePackedVarintField(int fieldNumber, int[] values) {
    if (values == null || values.length == 0) {
      return; // Empty packed arrays are omitted per protobuf3 spec
    }
    if (child == null) {
      child = new ProtobufEncoder();
    } else {
      child.reset();
    }
    for (int value : values) {
      child.writeVarint(value);
    }
    writeTag(fieldNumber, WIRETYPE_LENGTH_DELIMITED);
    writeVarint(child.size);
    if (size + child.size > buf.length) resize(child.size);
    System.arraycopy(child.buf, 0, buf, size, child.size);
    size += child.size;
  }

  public void writePackedVarintField(int fieldNumber, long[] values) {
    if (values == null || values.length == 0) {
      return;
    }
    if (child == null) {
      child = new ProtobufEncoder();
    } else {
      child.reset();
    }
    for (long value : values) {
      child.writeVarint(value);
    }
    writeTag(fieldNumber, WIRETYPE_LENGTH_DELIMITED);
    writeVarint(child.size);
    if (size + child.size > buf.length) resize(child.size);
    System.arraycopy(child.buf, 0, buf, size, child.size);
    size += child.size;
  }

  /** Single-value variant — avoids {@code new long[]{value}} allocation at call site. */
  public void writePackedVarintField(int fieldNumber, long value) {
    if (child == null) {
      child = new ProtobufEncoder();
    } else {
      child.reset();
    }
    child.writeVarint(value);
    writeTag(fieldNumber, WIRETYPE_LENGTH_DELIMITED);
    writeVarint(child.size);
    if (size + child.size > buf.length) resize(child.size);
    System.arraycopy(child.buf, 0, buf, size, child.size);
    size += child.size;
  }

  public void writePackedFixed64Field(int fieldNumber, long[] values) {
    if (values == null || values.length == 0) {
      return;
    }
    writeTag(fieldNumber, WIRETYPE_LENGTH_DELIMITED);
    writeVarint(values.length * 8L);
    for (long value : values) {
      writeFixed64(value);
    }
  }

  /** Single-value variant — avoids {@code new long[]{value}} allocation at call site. */
  public void writePackedFixed64Field(int fieldNumber, long value) {
    writeTag(fieldNumber, WIRETYPE_LENGTH_DELIMITED);
    writeVarint(8);
    writeFixed64(value);
  }

  public byte[] toByteArray() {
    return Arrays.copyOf(buf, size);
  }

  public void writeTo(OutputStream out) throws IOException {
    out.write(buf, 0, size);
  }

  public int size() {
    return size;
  }

  /** Functional interface for writing nested messages. */
  @FunctionalInterface
  public interface MessageWriter {
    void write(ProtobufEncoder encoder);
  }
}

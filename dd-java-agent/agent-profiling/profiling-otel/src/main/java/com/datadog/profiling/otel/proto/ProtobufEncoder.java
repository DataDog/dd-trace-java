package com.datadog.profiling.otel.proto;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

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

  private final ByteArrayOutputStream buffer;

  public ProtobufEncoder() {
    this.buffer = new ByteArrayOutputStream(4096);
  }

  public ProtobufEncoder(int initialCapacity) {
    this.buffer = new ByteArrayOutputStream(initialCapacity);
  }

  /** Resets the encoder for reuse. */
  public void reset() {
    buffer.reset();
  }

  /**
   * Writes a field tag (field number + wire type).
   *
   * @param fieldNumber the field number
   * @param wireType the wire type
   */
  public void writeTag(int fieldNumber, int wireType) {
    writeVarint((fieldNumber << 3) | wireType);
  }

  /**
   * Writes a varint (variable-length integer).
   *
   * @param value the value to write
   */
  public void writeVarint(long value) {
    while ((value & ~0x7FL) != 0) {
      buffer.write((int) ((value & 0x7F) | 0x80));
      value >>>= 7;
    }
    buffer.write((int) value);
  }

  /**
   * Writes a signed varint using ZigZag encoding.
   *
   * @param value the signed value to write
   */
  public void writeSignedVarint(long value) {
    writeVarint((value << 1) ^ (value >> 63));
  }

  /**
   * Writes a fixed 64-bit value (little-endian).
   *
   * @param value the value to write
   */
  public void writeFixed64(long value) {
    buffer.write((int) (value & 0xFF));
    buffer.write((int) ((value >> 8) & 0xFF));
    buffer.write((int) ((value >> 16) & 0xFF));
    buffer.write((int) ((value >> 24) & 0xFF));
    buffer.write((int) ((value >> 32) & 0xFF));
    buffer.write((int) ((value >> 40) & 0xFF));
    buffer.write((int) ((value >> 48) & 0xFF));
    buffer.write((int) ((value >> 56) & 0xFF));
  }

  /**
   * Writes a fixed 32-bit value (little-endian).
   *
   * @param value the value to write
   */
  public void writeFixed32(int value) {
    buffer.write(value & 0xFF);
    buffer.write((value >> 8) & 0xFF);
    buffer.write((value >> 16) & 0xFF);
    buffer.write((value >> 24) & 0xFF);
  }

  /**
   * Writes raw bytes.
   *
   * @param bytes the bytes to write
   */
  public void writeBytes(byte[] bytes) {
    writeVarint(bytes.length);
    try {
      buffer.write(bytes);
    } catch (IOException e) {
      // ByteArrayOutputStream doesn't throw IOException
      throw new RuntimeException(e);
    }
  }

  /**
   * Writes a string as length-delimited UTF-8 bytes.
   *
   * @param value the string to write
   */
  public void writeString(String value) {
    if (value == null || value.isEmpty()) {
      writeVarint(0);
      return;
    }
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    writeBytes(bytes);
  }

  /**
   * Writes a nested message. The message is first written to a temporary buffer to compute its
   * length, then written as a length-delimited field.
   *
   * @param fieldNumber the field number
   * @param writer the message writer
   */
  public void writeNestedMessage(int fieldNumber, MessageWriter writer) {
    // Write to temporary buffer to get length
    ProtobufEncoder nested = new ProtobufEncoder();
    writer.write(nested);
    byte[] messageBytes = nested.toByteArray();

    if (messageBytes.length > 0) {
      writeTag(fieldNumber, WIRETYPE_LENGTH_DELIMITED);
      writeVarint(messageBytes.length);
      try {
        buffer.write(messageBytes);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
  }

  /**
   * Writes a varint field.
   *
   * @param fieldNumber the field number
   * @param value the value
   */
  public void writeVarintField(int fieldNumber, long value) {
    if (value != 0) {
      writeTag(fieldNumber, WIRETYPE_VARINT);
      writeVarint(value);
    }
  }

  /**
   * Writes a signed varint field (ZigZag encoded).
   *
   * @param fieldNumber the field number
   * @param value the signed value
   */
  public void writeSignedVarintField(int fieldNumber, long value) {
    if (value != 0) {
      writeTag(fieldNumber, WIRETYPE_VARINT);
      writeSignedVarint(value);
    }
  }

  /**
   * Writes a fixed64 field.
   *
   * @param fieldNumber the field number
   * @param value the value
   */
  public void writeFixed64Field(int fieldNumber, long value) {
    if (value != 0) {
      writeTag(fieldNumber, WIRETYPE_FIXED64);
      writeFixed64(value);
    }
  }

  /**
   * Writes a fixed32 field.
   *
   * @param fieldNumber the field number
   * @param value the value
   */
  public void writeFixed32Field(int fieldNumber, int value) {
    if (value != 0) {
      writeTag(fieldNumber, WIRETYPE_FIXED32);
      writeFixed32(value);
    }
  }

  /**
   * Writes a string field.
   *
   * @param fieldNumber the field number
   * @param value the string value
   */
  public void writeStringField(int fieldNumber, String value) {
    if (value != null && !value.isEmpty()) {
      writeTag(fieldNumber, WIRETYPE_LENGTH_DELIMITED);
      writeString(value);
    }
  }

  /**
   * Writes a bytes field.
   *
   * @param fieldNumber the field number
   * @param value the bytes value
   */
  public void writeBytesField(int fieldNumber, byte[] value) {
    if (value != null && value.length > 0) {
      writeTag(fieldNumber, WIRETYPE_LENGTH_DELIMITED);
      writeBytes(value);
    }
  }

  /**
   * Writes a bytes field from an InputStream without loading entire content into memory.
   *
   * @param fieldNumber the field number
   * @param inputStream the input stream containing bytes to write (will be closed after writing)
   * @param length the number of bytes to read from the stream
   * @throws IOException if reading from stream fails
   */
  public void writeBytesField(int fieldNumber, InputStream inputStream, long length)
      throws IOException {
    if (inputStream == null || length == 0) {
      return;
    }

    writeTag(fieldNumber, WIRETYPE_LENGTH_DELIMITED);
    writeVarint(length);

    // Stream bytes directly to buffer
    byte[] chunk = new byte[8192];
    long remaining = length;
    try {
      while (remaining > 0) {
        int toRead = (int) Math.min(chunk.length, remaining);
        int read = inputStream.read(chunk, 0, toRead);
        if (read < 0) {
          throw new IOException("Unexpected end of stream");
        }
        buffer.write(chunk, 0, read);
        remaining -= read;
      }
    } finally {
      inputStream.close();
    }
  }

  /**
   * Writes a boolean field (as varint 0 or 1).
   *
   * @param fieldNumber the field number
   * @param value the boolean value
   */
  public void writeBoolField(int fieldNumber, boolean value) {
    if (value) {
      writeTag(fieldNumber, WIRETYPE_VARINT);
      writeVarint(1);
    }
  }

  /**
   * Writes a packed repeated int32/int64 field.
   *
   * @param fieldNumber the field number
   * @param values the values
   */
  public void writePackedVarintField(int fieldNumber, int[] values) {
    if (values == null || values.length == 0) {
      return;
    }

    // Calculate packed size
    ProtobufEncoder temp = new ProtobufEncoder();
    for (int value : values) {
      temp.writeVarint(value);
    }
    byte[] packed = temp.toByteArray();

    writeTag(fieldNumber, WIRETYPE_LENGTH_DELIMITED);
    writeVarint(packed.length);
    try {
      buffer.write(packed);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Writes a packed repeated int64 field.
   *
   * @param fieldNumber the field number
   * @param values the values
   */
  public void writePackedVarintField(int fieldNumber, long[] values) {
    if (values == null || values.length == 0) {
      return;
    }

    // Calculate packed size
    ProtobufEncoder temp = new ProtobufEncoder();
    for (long value : values) {
      temp.writeVarint(value);
    }
    byte[] packed = temp.toByteArray();

    writeTag(fieldNumber, WIRETYPE_LENGTH_DELIMITED);
    writeVarint(packed.length);
    try {
      buffer.write(packed);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Writes a packed repeated fixed64 field.
   *
   * @param fieldNumber the field number
   * @param values the values
   */
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

  /**
   * Returns the encoded bytes.
   *
   * @return the encoded protobuf bytes
   */
  public byte[] toByteArray() {
    return buffer.toByteArray();
  }

  /**
   * Writes the encoded bytes to the given output stream.
   *
   * @param out the output stream
   * @throws IOException if an I/O error occurs
   */
  public void writeTo(OutputStream out) throws IOException {
    buffer.writeTo(out);
  }

  /**
   * Returns the current size of the encoded data.
   *
   * @return the size in bytes
   */
  public int size() {
    return buffer.size();
  }

  /** Functional interface for writing nested messages. */
  @FunctionalInterface
  public interface MessageWriter {
    void write(ProtobufEncoder encoder);
  }
}

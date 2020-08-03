package com.datadog.mlt.io;

import java.nio.ByteBuffer;
import java.util.function.Consumer;
import java.util.function.Function;

public interface LEB128Writer {
  int EXT_BIT = 0x80;
  long COMPRESSED_INT_MASK = -EXT_BIT;

  /**
   * Execute the given code within the context of a new {@linkplain LEB128Writer} instance
   *
   * @param code the code to execute within the writer context
   */
  static void execute(Consumer<LEB128Writer> code) {
    LEB128ByteBufferWriter writer = new LEB128ByteBufferWriter();
    try {
      code.accept(writer);
    } finally {
      writer.reset();
    }
  }

  /**
   * Execute the given code within the context of a new {@linkplain LEB128Writer} instance
   *
   * @param code the code to execute within the writer context
   * @param <R> the return value type
   * @return the code execution result
   */
  static <R> R execute(Function<LEB128Writer, R> code) {
    LEB128ByteBufferWriter writer = new LEB128ByteBufferWriter();
    try {
      return code.apply(writer);
    } finally {
      writer.reset();
    }
  }

  /** Reset the writer. Discard any collected data and set position to 0. */
  void reset();

  /**
   * Write {@linkplain Character} data in LEB128 encoding
   *
   * @param data the data
   * @return the writer instance for chaining
   */
  LEB128Writer writeChar(char data);

  /**
   * Write {@linkplain Character} data in LEB128 encoding at the given offset
   *
   * @param offset the offset from which to start writing the data
   * @param data the data
   * @return the writer position after the data has been written
   */
  int writeChar(int offset, char data);

  /**
   * Write {@linkplain Short} data in LEB128 encoding
   *
   * @param data the data
   * @return the writer instance for chaining
   */
  LEB128Writer writeShort(short data);

  /**
   * Write {@linkplain Short} data in LEB128 encoding at the given offset
   *
   * @param offset the offset from which to start writing the data
   * @param data the data
   * @return the writer position after the data has been written
   */
  int writeShort(int offset, short data);

  /**
   * Write {@linkplain Integer} data in LEB128 encoding
   *
   * @param data the data
   * @return the writer instance for chaining
   */
  LEB128Writer writeInt(int data);

  /**
   * Write {@linkplain Integer} data in LEB128 encoding at the given offset
   *
   * @param offset the offset from which to start writing the data
   * @param data the data
   * @return the writer position after the data has been written
   */
  int writeInt(int offset, int data);

  /**
   * Write {@linkplain Long} data in LEB128 encoding
   *
   * @param data the data
   * @return the writer instance for chaining
   */
  LEB128Writer writeLong(long data);

  /**
   * Write {@linkplain Long} data in LEB128 encoding at the given offset
   *
   * @param offset the offset from which to start writing the data
   * @param data the data
   * @return the writer position after the data has been written
   */
  int writeLong(int offset, long data);

  /**
   * Write {@linkplain Float} data in default Java encoding
   *
   * @param data the data
   * @return the writer instance for chaining
   */
  LEB128Writer writeFloat(float data);

  /**
   * Write {@linkplain Float} data in default Java encoding at the given offset
   *
   * @param offset the offset from which to start writing the data
   * @param data the data
   * @return the writer position after the data has been written
   */
  int writeFloat(int offset, float data);

  /**
   * Write {@linkplain Double} data in default Java encoding
   *
   * @param data the data
   * @return the writer instance for chaining
   */
  LEB128Writer writeDouble(double data);

  /**
   * Write {@linkplain Double} data in default Java encoding at the given offset
   *
   * @param offset the offset from which to start writing the data
   * @param data the data
   * @return the writer position after the data has been written
   */
  int writeDouble(int offset, double data);

  /**
   * Write {@linkplain Boolean} data in default Java encoding
   *
   * @param data the data
   * @return the writer instance for chaining
   */
  LEB128Writer writeBoolean(boolean data);

  /**
   * Write {@linkplain Boolean} data in default Java encoding at the given offset
   *
   * @param offset the offset from which to start writing the data
   * @param data the data
   * @return the writer position after the data has been written
   */
  int writeBoolean(int offset, boolean data);

  /**
   * Write {@linkplain Byte} data
   *
   * @param data the data
   * @return the writer instance for chaining
   */
  LEB128Writer writeByte(byte data);

  /**
   * Write {@linkplain Byte} data at the given offset
   *
   * @param offset the offset from which to start writing the data
   * @param data the data
   * @return the writer position after the data has been written
   */
  int writeByte(int offset, byte data);

  /**
   * Write an array of {@linkplain Byte} elements
   *
   * @param data the data
   * @return the writer instance for chaining
   */
  LEB128Writer writeBytes(byte... data);

  /**
   * Write an array of {@linkplain Byte} elements at the given offset
   *
   * @param offset the offset from which to start writing the data
   * @param data the data
   * @return the writer position after the data has been written
   */
  int writeBytes(int offset, byte... data);

  /**
   * Write {@linkplain String} as a sequence of bytes representing UTF8 encoded string. The sequence
   * starts with LEB128 encoded int for the length of the sequence followed by the sequence bytes.
   *
   * @param data the data
   * @return the writer instance for chaining
   */
  LEB128Writer writeUTF(String data);

  /**
   * Write {@linkplain String} byte array data as a sequence of bytes representing UTF8 encoded
   * string. The sequence starts with LEB128 encoded int for the length of the sequence followed by
   * the sequence bytes.
   *
   * @param utf8Data the byte array representation of an UTF8 string
   * @return the writer instance for chaining
   */
  LEB128Writer writeUTF(byte[] utf8Data);

  /**
   * Write {@linkplain String} as a sequence of bytes representing UTF8 encoded string at the given
   * offset. The sequence starts with LEB128 encoded int for the length of the sequence followed by
   * the sequence bytes.
   *
   * @param offset the offset from which to start writing the data
   * @param data the data
   * @return the writer position after the data has been written
   */
  int writeUTF(int offset, String data);

  /**
   * Write {@linkplain String} byte array data at the given offset. The sequence starts with LEB128
   * encoded int for the length of the sequence followed by the sequence bytes.
   *
   * @param offset the offset from which to start writing the data
   * @param utf8Data the byte array representation of an UTF8 string
   * @return the writer position after the data has been written
   */
  int writeUTF(int offset, byte[] utf8Data);

  /**
   * Write {@linkplain String} byte array data in special encoding. The string will translate to
   * (byte)0 for {@literal null} value, (byte)1 for empty string and (byte)3 for the sequence of
   * bytes representing UTF8 encoded string. The sequence starts with LEB128 encoded int for the
   * length of the sequence followed by the sequence bytes.
   *
   * @param utf8Data the byte array representation of an UTF8 string
   * @return the writer instance for chaining
   */
  LEB128Writer writeCompactUTF(byte[] utf8Data);

  /**
   * Write {@linkplain String} as a sequence of bytes representing UTF8 encoded string at the given
   * offset. The sequence starts with LEB128 encoded int for the length of the sequence followed by
   * the sequence bytes.
   *
   * @param offset the offset from which to start writing the data
   * @param utf8Data the byte array representation of an UTF8 string
   * @return the writer position after the data has been written
   */
  int writeCompactUTF(int offset, byte[] utf8Data);

  /**
   * Write {@linkplain String} in special encoding. The string will translate to (byte)0 for
   * {@literal null} value, (byte)1 for empty string and (byte)3 for the sequence of bytes
   * representing UTF8 encoded string. The sequence starts with LEB128 encoded int for the length of
   * the sequence followed by the sequence bytes.
   *
   * @param data the data
   * @return the writer instance for chaining
   */
  LEB128Writer writeCompactUTF(String data);

  /**
   * Write {@linkplain String} byte array data in special encoding at the given offset. The string
   * will translate to (byte)0 for {@literal null} value, (byte)1 for empty string and (byte)3 for
   * the sequence of bytes representing UTF8 encoded string. The sequence starts with LEB128 encoded
   * int for the length of the sequence followed by the sequence bytes.
   *
   * @param offset the offset from which to start writing the data
   * @param data the data
   * @return the writer position after the data has been written
   */
  int writeCompactUTF(int offset, String data);

  /**
   * Write {@linkplain Short} data in default Java encoding
   *
   * @param data the data
   * @return the writer instance for chaining
   */
  LEB128Writer writeShortRaw(short data);

  /**
   * Write {@linkplain Short} data in default Java encoding at the given offset
   *
   * @param offset the offset from which to start writing the data
   * @param data the data
   * @return the writer position after the data has been written
   */
  int writeShortRaw(int offset, short data);

  /**
   * Write {@linkplain Integer} data in default Java encoding
   *
   * @param data the data
   * @return the writer instance for chaining
   */
  LEB128Writer writeIntRaw(int data);

  /**
   * Write {@linkplain Integer} data in default Java encoding at the given offset
   *
   * @param offset the offset from which to start writing the data
   * @param data the data
   * @return the writer position after the data has been written
   */
  int writeIntRaw(int offset, int data);

  /**
   * Write {@linkplain Long} data in default Java encoding
   *
   * @param data the data
   * @return the writer instance for chaining
   */
  LEB128Writer writeLongRaw(long data);

  /**
   * Write {@linkplain Long} data in default Java encoding at the given offset
   *
   * @param offset the offset from which to start writing the data
   * @param data the data
   * @return the writer position after the data has been written
   */
  int writeLongRaw(int offset, long data);

  /**
   * Transfer the written data to a byte array
   *
   * @return byte array containing the written data
   */
  default byte[] export() {
    final byte[][] dataRef = new byte[1][];
    export(
        buffer -> {
          int limit = buffer.limit();
          buffer.flip();
          int len = buffer.remaining();
          if (buffer.hasArray()) {
            dataRef[0] = new byte[len];
            System.arraycopy(
                buffer.array(), buffer.arrayOffset() + buffer.position(), dataRef[0], 0, len);
            buffer.position(buffer.limit());
          } else {
            dataRef[0] = new byte[len];
            buffer.get(dataRef[0]);
          }
          buffer.limit(limit);
        });
    return dataRef[0];
  }

  /**
   * Transfer the written data as a {@linkplain ByteBuffer}
   *
   * @param consumer a {@linkplain ByteBuffer} callback
   */
  void export(Consumer<ByteBuffer> consumer);

  /** @return current writer position */
  int position();

  /**
   * @return number of bytes written adjusted by the number of bytes necessary to encode the length
   *     itself
   */
  int length();

  /** @return the maximum number of bytes the writer can process */
  int capacity();
}

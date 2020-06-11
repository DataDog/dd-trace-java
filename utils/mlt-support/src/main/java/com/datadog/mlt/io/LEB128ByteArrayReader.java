package com.datadog.mlt.io;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Byte-array writer with default support for LEB128 encoded integer types */
final class LEB128ByteArrayReader {
  private static final int EXT_BIT = 0x80;
  private static final long COMPRESSED_INT_MASK = 0x7f;
  private final byte[] array;
  private int pointer = 0;

  LEB128ByteArrayReader(byte[] data) {
    array = Arrays.copyOf(data, data.length);
  }

  /** Reset the reader - set the reading position back to 0 */
  void reset() {
    pointer = 0;
  }

  /**
   * Check whether there is more data to read
   *
   * @return {@literal true} if there is more data to read
   */
  boolean hasMore() {
    return pointer < array.length;
  }

  /**
   * Get the current position and set the new one
   *
   * @param pos the new position
   * @return the previous position
   */
  int getAndSetPos(int pos) {
    if (pos > array.length) {
      throw new ArrayIndexOutOfBoundsException();
    }
    int current = pointer;
    pointer = pos;
    return current;
  }

  char readChar() {
    return (char) (readLong() & 0xffff);
  }

  short readShort() {
    return (short) (readLong() & 0xffff);
  }

  int readInt() {
    return (int) (readLong() & 0xffffffff);
  }

  long readLong() {
    long result = 0;
    short shift = 0;
    while (true) {
      byte b = readByte();
      result |= (b & COMPRESSED_INT_MASK) << shift;
      if ((b & EXT_BIT) == 0) {
        break;
      }
      shift += 7;
    }
    return result;
  }

  float readFloat() {
    int data = readIntRaw();
    return Float.intBitsToFloat(data);
  }

  double readDouble() {
    long data = readLongRaw();
    return Double.longBitsToDouble(data);
  }

  boolean readBoolean() {
    return readByte() != 0;
  }

  byte readByte() {
    return array[pointer++];
  }

  byte[] readBytes(int len) {
    byte[] data = new byte[len];
    for (int i = 0; i < len; i++) {
      data[i] = readByte();
    }
    return data;
  }

  String readUTF() {
    int size = readInt();
    byte[] data = readBytes(size);
    return new String(data, StandardCharsets.UTF_8);
  }

  short readShortRaw() {
    int data = 0;
    for (int i = 0; i < 2; i++) {
      data = (data << 8 | (readByte() & 0xff));
    }
    return (short) data;
  }

  int readIntRaw() {
    int data = 0;
    for (int i = 0; i < 4; i++) {
      data = (data << 8 | (readByte() & 0xff));
    }
    return data;
  }

  long readLongRaw() {
    long data = 0;
    for (int i = 0; i < 8; i++) {
      data = (data << 8 | (readByte() & 0xff));
    }
    return data;
  }

  /** @return current writer position */
  int position() {
    return pointer;
  }

  /** @return number of bytes in the input set */
  int size() {
    return array.length;
  }
}

package com.datadog.profiling.util;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class LEB128ByteArrayReader {
  private static final int EXT_BIT = 0x80;
  private static final long COMPRESSED_INT_MASK = 0x7f;
  private final byte[] array;
  private int pointer = 0;

  public LEB128ByteArrayReader(byte[] data) {
    array = Arrays.copyOf(data, data.length);
  }

  public void reset() {
    pointer = 0;
  }

  public boolean hasMore() {
    return pointer < array.length;
  }

  public int getAndSetPos(int pos) {
    if (pos > array.length) {
      throw new ArrayIndexOutOfBoundsException();
    }
    int current = pointer;
    pointer = pos;
    return current;
  }

  public char readChar() {
    return (char) (readLong() & 0xffff);
  }

  public short readShort() {
    return (short) (readLong() & 0xffff);
  }

  public int readInt() {
    return (int) (readLong() & 0xffffffff);
  }

  public long readLong() {
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

  public float readFloat() {
    int data = readIntRaw();
    return Float.intBitsToFloat(data);
  }

  public double readDouble() {
    long data = readLongRaw();
    return Double.longBitsToDouble(data);
  }

  public boolean readBoolean() {
    return readByte() != 0;
  }

  public byte readByte() {
    return array[pointer++];
  }

  public byte[] readBytes(int len) {
    byte[] data = new byte[len];
    for (int i = 0; i < len; i++) {
      data[i] = readByte();
    }
    return data;
  }

  public String readUTF() {
    int size = readInt();
    byte[] data = readBytes(size);
    return new String(data, StandardCharsets.UTF_8);
  }

  public short readShortRaw() {
    int data = 0;
    for (int i = 0; i < 2; i++) {
      data = (data << 8 | (readByte() & 0xff));
    }
    return (short) data;
  }

  public int readIntRaw() {
    int data = 0;
    for (int i = 0; i < 4; i++) {
      data = (data << 8 | (readByte() & 0xff));
    }
    return data;
  }

  public long readLongRaw() {
    long data = 0;
    for (int i = 0; i < 8; i++) {
      data = (data << 8 | (readByte() & 0xff));
    }
    return data;
  }

  /** @return current writer position */
  public int position() {
    return pointer;
  }

  /** @return number of bytes in the input set */
  public int size() {
    return array.length;
  }
}

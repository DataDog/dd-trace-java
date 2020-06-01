package com.datadog.profiling.util;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Byte-array writer with default support for LEB128 encoded integers */
public final class LEB128ByteArrayWriter {
  private static final int EXT_BIT = 0x80;
  private static final long COMPRESSED_INT_MASK = -EXT_BIT;
  private byte[] array;
  private int pointer = 0;

  public LEB128ByteArrayWriter(int size) {
    array = new byte[size];
  }

  public void reset() {
    Arrays.fill(array, (byte) 0);
    pointer = 0;
  }

  public LEB128ByteArrayWriter writeChar(char data) {
    writeChar(pointer, data);
    return this;
  }

  public long writeChar(long offset, char data) {
    return writeLong(offset, data & 0x000000000000ffffL);
  }

  public LEB128ByteArrayWriter writeShort(short data) {
    writeShort(pointer, data);
    return this;
  }

  public long writeShort(long offset, short data) {
    return writeLong(offset, data & 0x000000000000ffffL);
  }

  public LEB128ByteArrayWriter writeInt(int data) {
    writeInt(pointer, data);
    return this;
  }

  public long writeInt(long offset, int data) {
    return writeLong(offset, data & 0x00000000ffffffffL);
  }

  static int getPackedIntLen(long data) {
    if ((data & COMPRESSED_INT_MASK) == 0) {
      return 1;
    }
    data >>= 7;
    if ((data & COMPRESSED_INT_MASK) == 0) {
      return 2;
    }
    data >>= 7;
    if ((data & COMPRESSED_INT_MASK) == 0) {
      return 3;
    }
    data >>= 7;
    if ((data & COMPRESSED_INT_MASK) == 0) {
      return 4;
    }
    data >>= 7;
    if ((data & COMPRESSED_INT_MASK) == 0) {
      return 5;
    }
    data >>= 7;
    if ((data & COMPRESSED_INT_MASK) == 0) {
      return 6;
    }
    data >>= 7;
    if ((data & COMPRESSED_INT_MASK) == 0) {
      return 7;
    }
    data >>= 7;
    if ((data & COMPRESSED_INT_MASK) == 0) {
      return 8;
    }
    return 9;
  }

  public LEB128ByteArrayWriter writeLong(long data) {
    writeLong(pointer, data);
    return this;
  }

  public long writeLong(long offset, long data) {
    if ((data & COMPRESSED_INT_MASK) == 0) {
      return writeByte(offset, (byte) (data & 0xff));
    }
    offset = writeByte(offset, (byte) (data | EXT_BIT));
    data >>= 7;
    if ((data & COMPRESSED_INT_MASK) == 0) {
      return writeByte(offset, (byte) data);
    }
    offset = writeByte(offset, (byte) (data | EXT_BIT));
    data >>= 7;
    if ((data & COMPRESSED_INT_MASK) == 0) {
      return writeByte(offset, (byte) data);
    }
    offset = writeByte(offset, (byte) (data | EXT_BIT));
    data >>= 7;
    if ((data & COMPRESSED_INT_MASK) == 0) {
      return writeByte(offset, (byte) data);
    }
    offset = writeByte(offset, (byte) (data | EXT_BIT));
    data >>= 7;
    if ((data & COMPRESSED_INT_MASK) == 0) {
      return writeByte(offset, (byte) data);
    }
    offset = writeByte(offset, (byte) (data | EXT_BIT));
    data >>= 7;
    if ((data & COMPRESSED_INT_MASK) == 0) {
      return writeByte(offset, (byte) data);
    }
    offset = writeByte(offset, (byte) (data | EXT_BIT));
    data >>= 7;
    if ((data & COMPRESSED_INT_MASK) == 0) {
      return writeByte(offset, (byte) data);
    }
    offset = writeByte(offset, (byte) (data | EXT_BIT));
    data >>= 7;
    if ((data & COMPRESSED_INT_MASK) == 0) {
      return writeByte(offset, (byte) data);
    }
    offset = writeByte(offset, (byte) (data | EXT_BIT));
    return writeByte(offset, (byte) (data >> 7));
  }

  public LEB128ByteArrayWriter writeFloat(float data) {
    writeFloat(pointer, data);
    return this;
  }

  public long writeFloat(long offset, float data) {
    return writeIntRaw(offset, Float.floatToIntBits(data));
  }

  public LEB128ByteArrayWriter writeDouble(double data) {
    writeDouble(pointer, data);
    return this;
  }

  public long writeDouble(long offset, double data) {
    return writeLongRaw(offset, Double.doubleToLongBits(data));
  }

  public LEB128ByteArrayWriter writeBoolean(boolean data) {
    writeBoolean(pointer, data);
    return this;
  }

  public long writeBoolean(long offset, boolean data) {
    return writeByte(offset, data ? (byte) 1 : (byte) 0);
  }

  public LEB128ByteArrayWriter writeByte(byte data) {
    writeByte(pointer, data);
    return this;
  }

  public long writeByte(long offset, byte data) {
    int newOffset = (int) (offset + 1);
    if (newOffset >= array.length) {
      array = Arrays.copyOf(array, newOffset * 2);
    }
    array[(int) offset] = data;
    pointer = Math.max(newOffset, pointer);
    return newOffset;
  }

  public LEB128ByteArrayWriter writeBytes(byte... data) {
    writeBytes(pointer, data);
    return this;
  }

  public long writeBytes(long offset, byte... data) {
    int newOffset = (int) (offset + data.length);
    if (newOffset >= array.length) {
      array = Arrays.copyOf(array, newOffset * 2);
    }
    System.arraycopy(data, 0, array, (int) offset, data.length);
    pointer = Math.max(newOffset, pointer);
    return newOffset;
  }

  public LEB128ByteArrayWriter writeUTF(String data) {
    writeUTF(pointer, data);
    return this;
  }

  public long writeUTF(long offset, String data) {
    byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
    long pos = writeInt(offset, bytes.length);
    return writeBytes(pos, bytes);
  }

  public LEB128ByteArrayWriter writeCompactUTF(String data) {
    writeCompactUTF(pointer, data);
    return this;
  }

  public long writeCompactUTF(long offset, String data) {
    if (data == null) {
      return writeByte(offset, (byte) 0); // special NULL encoding
    }
    if (data.isEmpty()) {
      return writeByte(offset, (byte) 1); // special empty string encoding
    }
    long pos = writeByte(offset, (byte) 3); // UTF-8 string
    byte[] out = data.getBytes(StandardCharsets.UTF_8);
    pos = writeInt(pos, out.length);
    pos = writeBytes(pos, out);
    return pos;
  }

  public LEB128ByteArrayWriter writeShortRaw(short data) {
    writeShortRaw(pointer, data);
    return this;
  }

  public long writeShortRaw(long offset, short data) {
    return writeBytes(offset, (byte) ((data >> 8) & 0xff), (byte) (data & 0xff));
  }

  public LEB128ByteArrayWriter writeIntRaw(int data) {
    writeIntRaw(pointer, data);
    return this;
  }

  public long writeIntRaw(long offset, int data) {
    return writeBytes(
        offset,
        (byte) ((data >> 24) & 0xff),
        (byte) ((data >> 16) & 0xff),
        (byte) ((data >> 8) & 0xff),
        (byte) (data & 0xff));
  }

  public LEB128ByteArrayWriter writeLongRaw(long data) {
    writeLongRaw(pointer, data);
    return this;
  }

  public long writeLongRaw(long offset, long data) {
    return writeBytes(
        offset,
        (byte) ((data >> 56) & 0xff),
        (byte) ((data >> 48) & 0xff),
        (byte) ((data >> 40) & 0xff),
        (byte) ((data >> 32) & 0xff),
        (byte) ((data >> 24) & 0xff),
        (byte) ((data >> 16) & 0xff),
        (byte) ((data >> 8) & 0xff),
        (byte) (data & 0xff));
  }

  public byte[] toByteArray() {
    return Arrays.copyOf(array, pointer);
  }

  /** @return current writer position */
  public int position() {
    return pointer;
  }

  /**
   * @return number of bytes written adjusted by the number of bytes necessary to encode the length
   *     itself
   */
  public int length() {
    return adjustLength(pointer);
  }

  static int adjustLength(int length) {
    int extraLen = 0;
    do {
      extraLen = getPackedIntLen(length + extraLen);
    } while (getPackedIntLen(length + extraLen) != extraLen);
    return length + extraLen;
  }
}

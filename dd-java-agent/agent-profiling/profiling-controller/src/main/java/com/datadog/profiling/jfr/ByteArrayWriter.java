package com.datadog.profiling.jfr;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** JFR specific binary encoding writer. Data is written to auto-scaled byte-array. */
final class ByteArrayWriter {
  private static final int EXT_BIT = 0x80;
  private static final long COMPRESSED_INT_MASK = -EXT_BIT;
  private byte[] array;
  private int pointer = 0;

  ByteArrayWriter(int size) {
    array = new byte[size];
  }

  ByteArrayWriter writeChar(char data) {
    writeChar(pointer, data);
    return this;
  }

  long writeChar(long offset, char data) {
    return writeByte(offset, (byte) (data & 0xff));
  }

  ByteArrayWriter writeShort(short data) {
    writeShort(pointer, data);
    return this;
  }

  long writeShort(long offset, short data) {
    return writeLong(offset, data);
  }

  ByteArrayWriter writeInt(int data) {
    writeInt(pointer, data);
    return this;
  }

  long writeInt(long offset, int data) {
    return writeLong(offset, data);
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

  ByteArrayWriter writeLong(long data) {
    writeLong(pointer, data);
    return this;
  }

  long writeLong(long offset, long data) {
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

  ByteArrayWriter writeFloat(float data) {
    writeFloat(pointer, data);
    return this;
  }

  long writeFloat(long offset, float data) {
    return writeIntRaw(offset, Float.floatToIntBits(data));
  }

  ByteArrayWriter writeDouble(double data) {
    writeDouble(pointer, data);
    return this;
  }

  long writeDouble(long offset, double data) {
    return writeLongRaw(offset, Double.doubleToLongBits(data));
  }

  ByteArrayWriter writeBoolean(boolean data) {
    writeBoolean(pointer, data);
    return this;
  }

  long writeBoolean(long offset, boolean data) {
    return writeByte(offset, data ? (byte) 1 : (byte) 0);
  }

  ByteArrayWriter writeByte(byte data) {
    writeByte(pointer, data);
    return this;
  }

  long writeByte(long offset, byte data) {
    int newOffset = (int) (offset + 1);
    if (newOffset >= array.length) {
      array = Arrays.copyOf(array, newOffset * 2);
    }
    array[(int) offset] = data;
    pointer = Math.max(newOffset, pointer);
    return newOffset;
  }

  ByteArrayWriter writeBytes(byte... data) {
    writeBytes(pointer, data);
    return this;
  }

  long writeBytes(long offset, byte... data) {
    int newOffset = (int) (offset + data.length);
    if (newOffset >= array.length) {
      array = Arrays.copyOf(array, newOffset * 2);
    }
    System.arraycopy(data, 0, array, (int) offset, data.length);
    pointer = Math.max(newOffset, pointer);
    return newOffset;
  }

  ByteArrayWriter writeUTF(String data) {
    writeUTF(pointer, data);
    return this;
  }

  long writeUTF(long offset, String data) {
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

  ByteArrayWriter writeShortRaw(short data) {
    writeShortRaw(pointer, data);
    return this;
  }

  long writeShortRaw(long offset, short data) {
    return writeBytes(offset, (byte) ((data >> 8) & 0xff), (byte) (data & 0xff));
  }

  ByteArrayWriter writeIntRaw(int data) {
    writeIntRaw(pointer, data);
    return this;
  }

  long writeIntRaw(long offset, int data) {
    return writeBytes(
        offset,
        (byte) ((data >> 24) & 0xff),
        (byte) ((data >> 16) & 0xff),
        (byte) ((data >> 8) & 0xff),
        (byte) (data & 0xff));
  }

  ByteArrayWriter writeLongRaw(long data) {
    writeLongRaw(pointer, data);
    return this;
  }

  long writeLongRaw(long offset, long data) {
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

  byte[] toByteArray() {
    return Arrays.copyOf(array, pointer);
  }

  int length() {
    return pointer;
  }
}

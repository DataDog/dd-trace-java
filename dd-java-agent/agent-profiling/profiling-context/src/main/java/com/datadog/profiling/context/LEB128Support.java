package com.datadog.profiling.context;

import java.nio.ByteBuffer;

final class LEB128Support {
  private static final int EXT_BIT = 0x80;
  private static final long COMPRESSED_INT_MASK = -EXT_BIT;

  int align(int value, int alignment) {
    return value == 0 ? 0 : (((value - 1) / alignment) + 1) * alignment;
  }

  int varintSize(long value) {
    if (value >= 0 && value < 255) {
      return 1;
    }
    int pos = 63;
    long mask = 0xFE00000000000000L;
    long highBitMask = 0x8000000000000000L;
    while ((value & mask) == 0) {
      pos -= 7;
      mask = mask >>> 7;
      highBitMask = highBitMask >>> 7;
    }
    return Math.min((pos / 7) + (pos % 7 == 0 ? 0 : 1) + (((value & highBitMask) != 0 ? 1 : 0)), 9);
  }

  int longSize(long value) {
    int pos = 63;
    long mask = 0xFF00000000000000L;
    while (pos > 0 && (value & mask) == 0) {
      pos -= 8;
      mask = mask >>> 8;
    }
    return (pos / 8) + 1;
  }

  void putVarint(ByteBuffer buffer, long value) {
    //    if (value < 0) {
    //      // negative numbers are not supported
    //      return;
    //    }
    if ((value & COMPRESSED_INT_MASK) == 0) {
      buffer.put((byte) ((value & 0x7f)));
      return;
    }
    buffer.put((byte) ((value & 0x7f) | EXT_BIT));

    value >>= 7;
    if ((value & COMPRESSED_INT_MASK) == 0) {
      buffer.put((byte) ((value & 0x7f)));
      return;
    }
    buffer.put((byte) ((value & 0x7f) | EXT_BIT));

    value >>= 7;
    if ((value & COMPRESSED_INT_MASK) == 0) {
      buffer.put((byte) ((value & 0x7f)));
      return;
    }
    buffer.put((byte) ((value & 0x7f) | EXT_BIT));

    value >>= 7;
    if ((value & COMPRESSED_INT_MASK) == 0) {
      buffer.put((byte) ((value & 0x7f)));
      return;
    }
    buffer.put((byte) ((value & 0x7f) | EXT_BIT));

    value >>= 7;
    if ((value & COMPRESSED_INT_MASK) == 0) {
      buffer.put((byte) ((value & 0x7f)));
      return;
    }
    buffer.put((byte) ((value & 0x7f) | EXT_BIT));

    value >>= 7;
    if ((value & COMPRESSED_INT_MASK) == 0) {
      buffer.put((byte) ((value & 0x7f)));
      return;
    }
    buffer.put((byte) ((value & 0x7f) | EXT_BIT));

    value >>= 7;
    if ((value & COMPRESSED_INT_MASK) == 0) {
      buffer.put((byte) ((value & 0x7f)));
      return;
    }
    buffer.put((byte) ((value & 0x7f) | EXT_BIT));

    value >>= 7;
    if ((value & COMPRESSED_INT_MASK) == 0) {
      buffer.put((byte) ((value & 0x7f)));
      return;
    }
    buffer.put((byte) ((value & 0x7f) | EXT_BIT));

    buffer.put((byte) ((value >> 7) & 0x7f));
  }
}

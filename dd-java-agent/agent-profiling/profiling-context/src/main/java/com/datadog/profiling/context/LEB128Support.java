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
    while ((value & mask) == 0) {
      pos -= 7;
      mask = mask >>> 7;
    }
    return ((pos - 1) / 7) + 1;
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
    value >>= 7;
    buffer.put((byte) ((value & 0x7f) | EXT_BIT));

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

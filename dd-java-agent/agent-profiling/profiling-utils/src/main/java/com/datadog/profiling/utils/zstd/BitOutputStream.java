package com.datadog.profiling.utils.zstd;

import static com.datadog.profiling.utils.zstd.Util.checkArgument;
import static com.datadog.profiling.utils.zstd.ZstdConstants.SIZE_OF_LONG;

import datadog.trace.util.UnsafeUtils;

/** Bit-level writer to byte[] via UnsafeUtils. Uses a 64-bit accumulator. */
final class BitOutputStream {
  private static final long[] BIT_MASK = {
    0x0,
    0x1,
    0x3,
    0x7,
    0xF,
    0x1F,
    0x3F,
    0x7F,
    0xFF,
    0x1FF,
    0x3FF,
    0x7FF,
    0xFFF,
    0x1FFF,
    0x3FFF,
    0x7FFF,
    0xFFFF,
    0x1FFFF,
    0x3FFFF,
    0x7FFFF,
    0xFFFFF,
    0x1FFFFF,
    0x3FFFFF,
    0x7FFFFF,
    0xFFFFFF,
    0x1FFFFFF,
    0x3FFFFFF,
    0x7FFFFFF,
    0xFFFFFFF,
    0x1FFFFFFF,
    0x3FFFFFFF,
    0x7FFFFFFF
  }; // up to 31 bits

  private final Object outputBase;
  private final long outputAddress;
  private final long outputLimit;

  private long container;
  private int bitCount;
  private long currentAddress;

  BitOutputStream(Object outputBase, long outputAddress, int outputSize) {
    checkArgument(outputSize >= SIZE_OF_LONG, "Output buffer too small");

    this.outputBase = outputBase;
    this.outputAddress = outputAddress;
    outputLimit = this.outputAddress + outputSize - SIZE_OF_LONG;

    currentAddress = this.outputAddress;
  }

  void addBits(int value, int bits) {
    container |= (value & BIT_MASK[bits]) << bitCount;
    bitCount += bits;
  }

  /** Leading bits of value must be 0. */
  void addBitsFast(int value, int bits) {
    container |= ((long) value) << bitCount;
    bitCount += bits;
  }

  void flush() {
    int bytes = bitCount >>> 3;

    UnsafeUtils.putLong(outputBase, currentAddress, container);
    currentAddress += bytes;

    if (currentAddress > outputLimit) {
      currentAddress = outputLimit;
    }

    bitCount &= 7;
    container >>>= bytes * 8;
  }

  int close() {
    addBitsFast(1, 1); // end mark
    flush();

    if (currentAddress >= outputLimit) {
      return 0;
    }

    return (int) ((currentAddress - outputAddress) + (bitCount > 0 ? 1 : 0));
  }
}

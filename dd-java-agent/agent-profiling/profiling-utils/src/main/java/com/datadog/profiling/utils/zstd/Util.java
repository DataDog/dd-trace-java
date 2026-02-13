package com.datadog.profiling.utils.zstd;

import datadog.trace.util.UnsafeUtils;

final class Util {
  private Util() {}

  static int highestBit(int value) {
    return 31 - Integer.numberOfLeadingZeros(value);
  }

  static boolean isPowerOf2(int value) {
    return (value & (value - 1)) == 0;
  }

  static int mask(int bits) {
    return (1 << bits) - 1;
  }

  static void checkArgument(boolean condition, String reason) {
    if (!condition) {
      throw new IllegalArgumentException(reason);
    }
  }

  static void checkState(boolean condition, String reason) {
    if (!condition) {
      throw new IllegalStateException(reason);
    }
  }

  static void checkPositionIndexes(int start, int end, int size) {
    if (start < 0 || end < start || end > size) {
      throw new IndexOutOfBoundsException(
          "Range [" + start + ", " + end + ") out of bounds for size " + size);
    }
  }

  static int get24BitLittleEndian(Object inputBase, long inputAddress) {
    return (UnsafeUtils.getShort(inputBase, inputAddress) & 0xFFFF)
        | ((UnsafeUtils.getByte(inputBase, inputAddress + ZstdConstants.SIZE_OF_SHORT) & 0xFF)
            << Short.SIZE);
  }

  static void put24BitLittleEndian(Object outputBase, long outputAddress, int value) {
    UnsafeUtils.putShort(outputBase, outputAddress, (short) value);
    UnsafeUtils.putByte(
        outputBase, outputAddress + ZstdConstants.SIZE_OF_SHORT, (byte) (value >>> Short.SIZE));
  }

  static int minTableLog(int inputSize, int maxSymbolValue) {
    if (inputSize <= 1) {
      throw new IllegalArgumentException("Not supported. RLE should be used instead");
    }
    int minBitsSrc = highestBit(inputSize - 1) + 1;
    int minBitsSymbols = highestBit(maxSymbolValue) + 2;
    return Math.min(minBitsSrc, minBitsSymbols);
  }

  static int cycleLog(int hashLog, CompressionParameters.Strategy strategy) {
    int cycleLog = hashLog;
    if (strategy == CompressionParameters.Strategy.BTLAZY2
        || strategy == CompressionParameters.Strategy.BTOPT
        || strategy == CompressionParameters.Strategy.BTULTRA) {
      cycleLog = hashLog - 1;
    }
    return cycleLog;
  }
}

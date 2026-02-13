package com.datadog.profiling.utils.zstd;

import static com.datadog.profiling.utils.zstd.ZstdConstants.SIZE_OF_LONG;

import datadog.trace.util.UnsafeUtils;

/** Pre-allocated sequence accumulator for literal lengths, match lengths, and offsets. */
final class SequenceStore {
  final byte[] literalsBuffer;
  int literalsLength;

  final int[] offsets;
  final int[] literalLengths;
  final int[] matchLengths;
  int sequenceCount;

  final byte[] literalLengthCodes;
  final byte[] matchLengthCodes;
  final byte[] offsetCodes;

  LongField longLengthField;
  int longLengthPosition;

  enum LongField {
    LITERAL,
    MATCH
  }

  // @formatter:off
  private static final byte[] LITERAL_LENGTH_CODE = {
    0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
    16, 16, 17, 17, 18, 18, 19, 19, 20, 20, 20, 20, 21, 21, 21, 21,
    22, 22, 22, 22, 22, 22, 22, 22, 23, 23, 23, 23, 23, 23, 23, 23,
    24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24
  };

  private static final byte[] MATCH_LENGTH_CODE = {
    0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
    16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31,
    32, 32, 33, 33, 34, 34, 35, 35, 36, 36, 36, 36, 37, 37, 37, 37,
    38, 38, 38, 38, 38, 38, 38, 38, 39, 39, 39, 39, 39, 39, 39, 39,
    40, 40, 40, 40, 40, 40, 40, 40, 40, 40, 40, 40, 40, 40, 40, 40,
    41, 41, 41, 41, 41, 41, 41, 41, 41, 41, 41, 41, 41, 41, 41, 41,
    42, 42, 42, 42, 42, 42, 42, 42, 42, 42, 42, 42, 42, 42, 42, 42,
    42, 42, 42, 42, 42, 42, 42, 42, 42, 42, 42, 42, 42, 42, 42, 42
  };

  // @formatter:on

  SequenceStore(int blockSize, int maxSequences) {
    offsets = new int[maxSequences];
    literalLengths = new int[maxSequences];
    matchLengths = new int[maxSequences];

    literalLengthCodes = new byte[maxSequences];
    matchLengthCodes = new byte[maxSequences];
    offsetCodes = new byte[maxSequences];

    literalsBuffer = new byte[blockSize];

    reset();
  }

  void appendLiterals(Object inputBase, long inputAddress, int inputSize) {
    UnsafeUtils.copyMemory(
        inputBase,
        inputAddress,
        literalsBuffer,
        UnsafeUtils.BYTE_ARRAY_BASE_OFFSET + literalsLength,
        inputSize);
    literalsLength += inputSize;
  }

  void storeSequence(
      Object literalBase,
      long literalAddress,
      int literalLength,
      int offsetCode,
      int matchLengthBase) {
    long input = literalAddress;
    long output = UnsafeUtils.BYTE_ARRAY_BASE_OFFSET + literalsLength;
    int copied = 0;
    do {
      UnsafeUtils.putLong(literalsBuffer, output, UnsafeUtils.getLong(literalBase, input));
      input += SIZE_OF_LONG;
      output += SIZE_OF_LONG;
      copied += SIZE_OF_LONG;
    } while (copied < literalLength);

    literalsLength += literalLength;

    if (literalLength > 65535) {
      longLengthField = LongField.LITERAL;
      longLengthPosition = sequenceCount;
    }
    literalLengths[sequenceCount] = literalLength;

    offsets[sequenceCount] = offsetCode + 1;

    if (matchLengthBase > 65535) {
      longLengthField = LongField.MATCH;
      longLengthPosition = sequenceCount;
    }

    matchLengths[sequenceCount] = matchLengthBase;

    sequenceCount++;
  }

  void reset() {
    literalsLength = 0;
    sequenceCount = 0;
    longLengthField = null;
  }

  void generateCodes() {
    for (int i = 0; i < sequenceCount; ++i) {
      literalLengthCodes[i] = (byte) literalLengthToCode(literalLengths[i]);
      offsetCodes[i] = (byte) Util.highestBit(offsets[i]);
      matchLengthCodes[i] = (byte) matchLengthToCode(matchLengths[i]);
    }

    if (longLengthField == LongField.LITERAL) {
      literalLengthCodes[longLengthPosition] = ZstdConstants.MAX_LITERALS_LENGTH_SYMBOL;
    }
    if (longLengthField == LongField.MATCH) {
      matchLengthCodes[longLengthPosition] = ZstdConstants.MAX_MATCH_LENGTH_SYMBOL;
    }
  }

  private static int literalLengthToCode(int literalLength) {
    if (literalLength >= 64) {
      return Util.highestBit(literalLength) + 19;
    } else {
      return LITERAL_LENGTH_CODE[literalLength];
    }
  }

  private static int matchLengthToCode(int matchLengthBase) {
    if (matchLengthBase >= 128) {
      return Util.highestBit(matchLengthBase) + 36;
    } else {
      return MATCH_LENGTH_CODE[matchLengthBase];
    }
  }
}

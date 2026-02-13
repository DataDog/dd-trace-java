package com.datadog.profiling.utils.zstd;

import static com.datadog.profiling.utils.zstd.ZstdConstants.SIZE_OF_INT;
import static com.datadog.profiling.utils.zstd.ZstdConstants.SIZE_OF_LONG;

import datadog.trace.util.UnsafeUtils;

/** Dual-hash-table match finder (DoubleFast / DFAST strategy). Memory-bound, no ASM needed. */
final class DoubleFastBlockCompressor implements BlockCompressor {
  private static final int MIN_MATCH = 3;
  private static final int SEARCH_STRENGTH = 8;
  private static final int REP_MOVE = ZstdConstants.REPEATED_OFFSET_COUNT - 1;

  @Override
  public int compressBlock(
      Object inputBase,
      long inputAddress,
      int inputSize,
      SequenceStore output,
      BlockCompressionState state,
      RepeatedOffsets offsets,
      CompressionParameters parameters) {
    int matchSearchLength = Math.max(parameters.getSearchLength(), 4);

    final long baseAddress = state.getBaseAddress();
    final long windowBaseAddress = baseAddress + state.getWindowBaseOffset();

    int[] longHashTable = state.hashTable;
    int longHashBits = parameters.getHashLog();

    int[] shortHashTable = state.chainTable;
    int shortHashBits = parameters.getChainLog();

    final long inputEnd = inputAddress + inputSize;
    final long inputLimit = inputEnd - SIZE_OF_LONG;

    long input = inputAddress;
    long anchor = inputAddress;

    int offset1 = offsets.getOffset0();
    int offset2 = offsets.getOffset1();

    int savedOffset = 0;

    if (input - windowBaseAddress == 0) {
      input++;
    }
    int maxRep = (int) (input - windowBaseAddress);

    if (offset2 > maxRep) {
      savedOffset = offset2;
      offset2 = 0;
    }

    if (offset1 > maxRep) {
      savedOffset = offset1;
      offset1 = 0;
    }

    while (input < inputLimit) {
      int shortHash = hash(inputBase, input, shortHashBits, matchSearchLength);
      long shortMatchAddress = baseAddress + shortHashTable[shortHash];

      int longHash = hash8(UnsafeUtils.getLong(inputBase, input), longHashBits);
      long longMatchAddress = baseAddress + longHashTable[longHash];

      int current = (int) (input - baseAddress);
      longHashTable[longHash] = current;
      shortHashTable[shortHash] = current;

      int matchLength;
      int offset;

      if (offset1 > 0
          && UnsafeUtils.getInt(inputBase, input + 1 - offset1)
              == UnsafeUtils.getInt(inputBase, input + 1)) {
        matchLength =
            count(inputBase, input + 1 + SIZE_OF_INT, inputEnd, input + 1 + SIZE_OF_INT - offset1)
                + SIZE_OF_INT;
        input++;
        output.storeSequence(inputBase, anchor, (int) (input - anchor), 0, matchLength - MIN_MATCH);
      } else {
        if (longMatchAddress > windowBaseAddress
            && UnsafeUtils.getLong(inputBase, longMatchAddress)
                == UnsafeUtils.getLong(inputBase, input)) {
          matchLength =
              count(inputBase, input + SIZE_OF_LONG, inputEnd, longMatchAddress + SIZE_OF_LONG)
                  + SIZE_OF_LONG;
          offset = (int) (input - longMatchAddress);
          while (input > anchor
              && longMatchAddress > windowBaseAddress
              && UnsafeUtils.getByte(inputBase, input - 1)
                  == UnsafeUtils.getByte(inputBase, longMatchAddress - 1)) {
            input--;
            longMatchAddress--;
            matchLength++;
          }
        } else {
          if (shortMatchAddress > windowBaseAddress
              && UnsafeUtils.getInt(inputBase, shortMatchAddress)
                  == UnsafeUtils.getInt(inputBase, input)) {
            int nextOffsetHash = hash8(UnsafeUtils.getLong(inputBase, input + 1), longHashBits);
            long nextOffsetMatchAddress = baseAddress + longHashTable[nextOffsetHash];
            longHashTable[nextOffsetHash] = current + 1;

            if (nextOffsetMatchAddress > windowBaseAddress
                && UnsafeUtils.getLong(inputBase, nextOffsetMatchAddress)
                    == UnsafeUtils.getLong(inputBase, input + 1)) {
              matchLength =
                  count(
                          inputBase,
                          input + 1 + SIZE_OF_LONG,
                          inputEnd,
                          nextOffsetMatchAddress + SIZE_OF_LONG)
                      + SIZE_OF_LONG;
              input++;
              offset = (int) (input - nextOffsetMatchAddress);
              while (input > anchor
                  && nextOffsetMatchAddress > windowBaseAddress
                  && UnsafeUtils.getByte(inputBase, input - 1)
                      == UnsafeUtils.getByte(inputBase, nextOffsetMatchAddress - 1)) {
                input--;
                nextOffsetMatchAddress--;
                matchLength++;
              }
            } else {
              matchLength =
                  count(inputBase, input + SIZE_OF_INT, inputEnd, shortMatchAddress + SIZE_OF_INT)
                      + SIZE_OF_INT;
              offset = (int) (input - shortMatchAddress);
              while (input > anchor
                  && shortMatchAddress > windowBaseAddress
                  && UnsafeUtils.getByte(inputBase, input - 1)
                      == UnsafeUtils.getByte(inputBase, shortMatchAddress - 1)) {
                input--;
                shortMatchAddress--;
                matchLength++;
              }
            }
          } else {
            input += ((input - anchor) >> SEARCH_STRENGTH) + 1;
            continue;
          }
        }

        offset2 = offset1;
        offset1 = offset;

        output.storeSequence(
            inputBase, anchor, (int) (input - anchor), offset + REP_MOVE, matchLength - MIN_MATCH);
      }

      input += matchLength;
      anchor = input;

      if (input <= inputLimit) {
        longHashTable[
                hash8(UnsafeUtils.getLong(inputBase, baseAddress + current + 2), longHashBits)] =
            current + 2;
        shortHashTable[
                hash(inputBase, baseAddress + current + 2, shortHashBits, matchSearchLength)] =
            current + 2;

        longHashTable[hash8(UnsafeUtils.getLong(inputBase, input - 2), longHashBits)] =
            (int) (input - 2 - baseAddress);
        shortHashTable[hash(inputBase, input - 2, shortHashBits, matchSearchLength)] =
            (int) (input - 2 - baseAddress);

        while (input <= inputLimit
            && offset2 > 0
            && UnsafeUtils.getInt(inputBase, input)
                == UnsafeUtils.getInt(inputBase, input - offset2)) {
          int repetitionLength =
              count(inputBase, input + SIZE_OF_INT, inputEnd, input + SIZE_OF_INT - offset2)
                  + SIZE_OF_INT;

          int temp = offset2;
          offset2 = offset1;
          offset1 = temp;

          shortHashTable[hash(inputBase, input, shortHashBits, matchSearchLength)] =
              (int) (input - baseAddress);
          longHashTable[hash8(UnsafeUtils.getLong(inputBase, input), longHashBits)] =
              (int) (input - baseAddress);

          output.storeSequence(inputBase, anchor, 0, 0, repetitionLength - MIN_MATCH);

          input += repetitionLength;
          anchor = input;
        }
      }
    }

    offsets.saveOffset0(offset1 != 0 ? offset1 : savedOffset);
    offsets.saveOffset1(offset2 != 0 ? offset2 : savedOffset);

    return (int) (inputEnd - anchor);
  }

  static int count(
      Object inputBase, final long inputAddress, final long inputLimit, final long matchAddress) {
    long input = inputAddress;
    long match = matchAddress;

    int remaining = (int) (inputLimit - inputAddress);

    int count = 0;
    while (count < remaining - (SIZE_OF_LONG - 1)) {
      long diff = UnsafeUtils.getLong(inputBase, match) ^ UnsafeUtils.getLong(inputBase, input);
      if (diff != 0) {
        return count + (Long.numberOfTrailingZeros(diff) >> 3);
      }

      count += SIZE_OF_LONG;
      input += SIZE_OF_LONG;
      match += SIZE_OF_LONG;
    }

    while (count < remaining
        && UnsafeUtils.getByte(inputBase, match) == UnsafeUtils.getByte(inputBase, input)) {
      count++;
      input++;
      match++;
    }

    return count;
  }

  private static int hash(Object inputBase, long inputAddress, int bits, int matchSearchLength) {
    switch (matchSearchLength) {
      case 8:
        return hash8(UnsafeUtils.getLong(inputBase, inputAddress), bits);
      case 7:
        return hash7(UnsafeUtils.getLong(inputBase, inputAddress), bits);
      case 6:
        return hash6(UnsafeUtils.getLong(inputBase, inputAddress), bits);
      case 5:
        return hash5(UnsafeUtils.getLong(inputBase, inputAddress), bits);
      default:
        return hash4(UnsafeUtils.getInt(inputBase, inputAddress), bits);
    }
  }

  private static final int PRIME_4_BYTES = 0x9E3779B1;
  private static final long PRIME_5_BYTES = 0xCF1BBCDCBBL;
  private static final long PRIME_6_BYTES = 0xCF1BBCDCBF9BL;
  private static final long PRIME_7_BYTES = 0xCF1BBCDCBFA563L;
  private static final long PRIME_8_BYTES = 0xCF1BBCDCB7A56463L;

  private static int hash4(int value, int bits) {
    return (value * PRIME_4_BYTES) >>> (Integer.SIZE - bits);
  }

  private static int hash5(long value, int bits) {
    return (int) (((value << (Long.SIZE - 40)) * PRIME_5_BYTES) >>> (Long.SIZE - bits));
  }

  private static int hash6(long value, int bits) {
    return (int) (((value << (Long.SIZE - 48)) * PRIME_6_BYTES) >>> (Long.SIZE - bits));
  }

  private static int hash7(long value, int bits) {
    return (int) (((value << (Long.SIZE - 56)) * PRIME_7_BYTES) >>> (Long.SIZE - bits));
  }

  private static int hash8(long value, int bits) {
    return (int) ((value * PRIME_8_BYTES) >>> (Long.SIZE - bits));
  }
}

package com.datadog.profiling.utils.zstd;

import static com.datadog.profiling.utils.zstd.Util.checkArgument;
import static com.datadog.profiling.utils.zstd.ZstdConstants.SIZE_OF_LONG;
import static com.datadog.profiling.utils.zstd.ZstdConstants.SIZE_OF_SHORT;

import datadog.trace.util.UnsafeUtils;

/** FSE compression: normalization, table writing, and two-stream encoding. */
final class FiniteStateEntropy {
  static final int MAX_SYMBOL = 255;
  static final int MAX_TABLE_LOG = 12;
  static final int MIN_TABLE_LOG = 5;

  private static final int[] REST_TO_BEAT = {0, 473195, 504333, 520860, 550000, 700000, 750000, 830000};
  private static final short UNASSIGNED = -2;

  private FiniteStateEntropy() {}

  static int compress(
      Object outputBase,
      long outputAddress,
      int outputSize,
      byte[] input,
      int inputSize,
      FseCompressionTable table) {
    return compress(
        outputBase,
        outputAddress,
        outputSize,
        input,
        UnsafeUtils.BYTE_ARRAY_BASE_OFFSET,
        inputSize,
        table);
  }

  static int compress(
      Object outputBase,
      long outputAddress,
      int outputSize,
      Object inputBase,
      long inputAddress,
      int inputSize,
      FseCompressionTable table) {
    checkArgument(outputSize >= SIZE_OF_LONG, "Output buffer too small");

    final long start = inputAddress;
    final long inputLimit = start + inputSize;

    long input = inputLimit;

    if (inputSize <= 2) {
      return 0;
    }

    BitOutputStream stream = new BitOutputStream(outputBase, outputAddress, outputSize);

    int state1;
    int state2;

    if ((inputSize & 1) != 0) {
      input--;
      state1 = table.begin(UnsafeUtils.getByte(inputBase, input));

      input--;
      state2 = table.begin(UnsafeUtils.getByte(inputBase, input));

      input--;
      state1 = table.encode(stream, state1, UnsafeUtils.getByte(inputBase, input));

      stream.flush();
    } else {
      input--;
      state2 = table.begin(UnsafeUtils.getByte(inputBase, input));

      input--;
      state1 = table.begin(UnsafeUtils.getByte(inputBase, input));
    }

    // join to mod 4
    inputSize -= 2;

    if ((SIZE_OF_LONG * 8 > MAX_TABLE_LOG * 4 + 7) && (inputSize & 2) != 0) {
      input--;
      state2 = table.encode(stream, state2, UnsafeUtils.getByte(inputBase, input));

      input--;
      state1 = table.encode(stream, state1, UnsafeUtils.getByte(inputBase, input));

      stream.flush();
    }

    // 2 or 4 encoding per loop
    while (input > start) {
      input--;
      state2 = table.encode(stream, state2, UnsafeUtils.getByte(inputBase, input));

      if (SIZE_OF_LONG * 8 < MAX_TABLE_LOG * 2 + 7) {
        stream.flush();
      }

      input--;
      state1 = table.encode(stream, state1, UnsafeUtils.getByte(inputBase, input));

      if (SIZE_OF_LONG * 8 > MAX_TABLE_LOG * 4 + 7) {
        input--;
        state2 = table.encode(stream, state2, UnsafeUtils.getByte(inputBase, input));

        input--;
        state1 = table.encode(stream, state1, UnsafeUtils.getByte(inputBase, input));
      }

      stream.flush();
    }

    table.finish(stream, state2);
    table.finish(stream, state1);

    return stream.close();
  }

  static int optimalTableLog(int maxTableLog, int inputSize, int maxSymbol) {
    if (inputSize <= 1) {
      throw new IllegalArgumentException("Not supported. Use RLE instead");
    }

    int result = maxTableLog;
    result = Math.min(result, Util.highestBit(inputSize - 1) - 2);
    result = Math.max(result, Util.minTableLog(inputSize, maxSymbol));
    result = Math.max(result, MIN_TABLE_LOG);
    result = Math.min(result, MAX_TABLE_LOG);

    return result;
  }

  static int normalizeCounts(
      short[] normalizedCounts, int tableLog, int[] counts, int total, int maxSymbol) {
    checkArgument(tableLog >= MIN_TABLE_LOG, "Unsupported FSE table size");
    checkArgument(tableLog <= MAX_TABLE_LOG, "FSE table size too large");
    checkArgument(tableLog >= Util.minTableLog(total, maxSymbol), "FSE table size too small");

    long scale = 62 - tableLog;
    long step = (1L << 62) / total;
    long vstep = 1L << (scale - 20);

    int stillToDistribute = 1 << tableLog;

    int largest = 0;
    short largestProbability = 0;
    int lowThreshold = total >>> tableLog;

    for (int symbol = 0; symbol <= maxSymbol; symbol++) {
      if (counts[symbol] == total) {
        throw new IllegalArgumentException("Should have been RLE-compressed");
      }
      if (counts[symbol] == 0) {
        normalizedCounts[symbol] = 0;
        continue;
      }
      if (counts[symbol] <= lowThreshold) {
        normalizedCounts[symbol] = -1;
        stillToDistribute--;
      } else {
        short probability = (short) ((counts[symbol] * step) >>> scale);
        if (probability < 8) {
          long restToBeat = vstep * REST_TO_BEAT[probability];
          long delta = counts[symbol] * step - (((long) probability) << scale);
          if (delta > restToBeat) {
            probability++;
          }
        }
        if (probability > largestProbability) {
          largestProbability = probability;
          largest = symbol;
        }
        normalizedCounts[symbol] = probability;
        stillToDistribute -= probability;
      }
    }

    if (-stillToDistribute >= (normalizedCounts[largest] >>> 1)) {
      normalizeCounts2(normalizedCounts, tableLog, counts, total, maxSymbol);
    } else {
      normalizedCounts[largest] += (short) stillToDistribute;
    }

    return tableLog;
  }

  static void normalizeCounts2(
      short[] normalizedCounts, int tableLog, int[] counts, int total, int maxSymbol) {
    int distributed = 0;

    int lowThreshold = total >>> tableLog;
    int lowOne = (total * 3) >>> (tableLog + 1);

    for (int i = 0; i <= maxSymbol; i++) {
      if (counts[i] == 0) {
        normalizedCounts[i] = 0;
      } else if (counts[i] <= lowThreshold) {
        normalizedCounts[i] = -1;
        distributed++;
        total -= counts[i];
      } else if (counts[i] <= lowOne) {
        normalizedCounts[i] = 1;
        distributed++;
        total -= counts[i];
      } else {
        normalizedCounts[i] = UNASSIGNED;
      }
    }

    int normalizationFactor = 1 << tableLog;
    int toDistribute = normalizationFactor - distributed;

    if ((total / toDistribute) > lowOne) {
      lowOne = ((total * 3) / (toDistribute * 2));
      for (int i = 0; i <= maxSymbol; i++) {
        if ((normalizedCounts[i] == UNASSIGNED) && (counts[i] <= lowOne)) {
          normalizedCounts[i] = 1;
          distributed++;
          total -= counts[i];
        }
      }
      toDistribute = normalizationFactor - distributed;
    }

    if (distributed == maxSymbol + 1) {
      int maxValue = 0;
      int maxCount = 0;
      for (int i = 0; i <= maxSymbol; i++) {
        if (counts[i] > maxCount) {
          maxValue = i;
          maxCount = counts[i];
        }
      }
      normalizedCounts[maxValue] += (short) toDistribute;
      return;
    }

    if (total == 0) {
      for (int i = 0; toDistribute > 0; i = (i + 1) % (maxSymbol + 1)) {
        if (normalizedCounts[i] > 0) {
          toDistribute--;
          normalizedCounts[i]++;
        }
      }
      return;
    }

    long vStepLog = 62 - tableLog;
    long mid = (1L << (vStepLog - 1)) - 1;
    long rStep = (((1L << vStepLog) * toDistribute) + mid) / total;
    long tmpTotal = mid;
    for (int i = 0; i <= maxSymbol; i++) {
      if (normalizedCounts[i] == UNASSIGNED) {
        long end = tmpTotal + (counts[i] * rStep);
        int sStart = (int) (tmpTotal >>> vStepLog);
        int sEnd = (int) (end >>> vStepLog);
        int weight = sEnd - sStart;

        if (weight < 1) {
          throw new AssertionError();
        }
        normalizedCounts[i] = (short) weight;
        tmpTotal = end;
      }
    }
  }

  static int writeNormalizedCounts(
      Object outputBase,
      long outputAddress,
      int outputSize,
      short[] normalizedCounts,
      int maxSymbol,
      int tableLog) {
    checkArgument(tableLog <= MAX_TABLE_LOG, "FSE table too large");
    checkArgument(tableLog >= MIN_TABLE_LOG, "FSE table too small");

    long output = outputAddress;
    long outputLimit = outputAddress + outputSize;

    int tableSize = 1 << tableLog;

    int bitCount = 0;

    // encode table size
    int bitStream = (tableLog - MIN_TABLE_LOG);
    bitCount += 4;

    int remaining = tableSize + 1; // +1 for extra accuracy
    int threshold = tableSize;
    int tableBitCount = tableLog + 1;

    int symbol = 0;

    boolean previousIs0 = false;
    while (remaining > 1) {
      if (previousIs0) {
        int start = symbol;

        // find run of symbols with count 0
        while (normalizedCounts[symbol] == 0) {
          symbol++;
        }

        // encode in batches of 8 repeat sequences (24 symbols)
        while (symbol >= start + 24) {
          start += 24;
          bitStream |= (0b11_11_11_11_11_11_11_11 << bitCount);
          checkArgument(output + SIZE_OF_SHORT <= outputLimit, "Output buffer too small");

          UnsafeUtils.putShort(outputBase, output, (short) bitStream);
          output += SIZE_OF_SHORT;

          bitStream >>>= Short.SIZE;
        }

        // encode remaining in batches of 3 symbols
        while (symbol >= start + 3) {
          start += 3;
          bitStream |= 0b11 << bitCount;
          bitCount += 2;
        }

        // encode tail
        bitStream |= (symbol - start) << bitCount;
        bitCount += 2;

        // flush bitstream if necessary
        if (bitCount > 16) {
          checkArgument(output + SIZE_OF_SHORT <= outputLimit, "Output buffer too small");

          UnsafeUtils.putShort(outputBase, output, (short) bitStream);
          output += SIZE_OF_SHORT;

          bitStream >>>= Short.SIZE;
          bitCount -= Short.SIZE;
        }
      }

      int count = normalizedCounts[symbol++];
      int max = (2 * threshold - 1) - remaining;
      remaining -= count < 0 ? -count : count;
      count++; // +1 for extra accuracy
      if (count >= threshold) {
        count += max;
      }
      bitStream |= count << bitCount;
      bitCount += tableBitCount;
      bitCount -= (count < max ? 1 : 0);
      previousIs0 = (count == 1);

      if (remaining < 1) {
        throw new AssertionError();
      }

      while (remaining < threshold) {
        tableBitCount--;
        threshold >>= 1;
      }

      // flush bitstream if necessary
      if (bitCount > 16) {
        checkArgument(output + SIZE_OF_SHORT <= outputLimit, "Output buffer too small");

        UnsafeUtils.putShort(outputBase, output, (short) bitStream);
        output += SIZE_OF_SHORT;

        bitStream >>>= Short.SIZE;
        bitCount -= Short.SIZE;
      }
    }

    // flush remaining bitstream
    checkArgument(output + SIZE_OF_SHORT <= outputLimit, "Output buffer too small");
    UnsafeUtils.putShort(outputBase, output, (short) bitStream);
    output += (bitCount + 7) / 8;

    checkArgument(symbol <= maxSymbol + 1, "Symbol count exceeds maxSymbol");

    return (int) (output - outputAddress);
  }
}

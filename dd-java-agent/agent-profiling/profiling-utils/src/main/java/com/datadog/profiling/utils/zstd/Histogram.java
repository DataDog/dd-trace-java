package com.datadog.profiling.utils.zstd;

import datadog.trace.util.UnsafeUtils;
import java.util.Arrays;

/** Symbol frequency counting over byte[]. */
final class Histogram {
  private Histogram() {}

  private static void count(Object inputBase, long inputAddress, int inputSize, int[] counts) {
    long input = inputAddress;

    Arrays.fill(counts, 0);

    for (int i = 0; i < inputSize; i++) {
      int symbol = UnsafeUtils.getByte(inputBase, input) & 0xFF;
      input++;
      counts[symbol]++;
    }
  }

  static int findLargestCount(int[] counts, int maxSymbol) {
    int max = 0;
    for (int i = 0; i <= maxSymbol; i++) {
      if (counts[i] > max) {
        max = counts[i];
      }
    }
    return max;
  }

  static int findMaxSymbol(int[] counts, int maxSymbol) {
    while (maxSymbol > 0 && counts[maxSymbol] == 0) {
      maxSymbol--;
    }
    return maxSymbol;
  }

  static void count(byte[] input, int length, int[] counts) {
    count(input, UnsafeUtils.BYTE_ARRAY_BASE_OFFSET, length, counts);
  }
}

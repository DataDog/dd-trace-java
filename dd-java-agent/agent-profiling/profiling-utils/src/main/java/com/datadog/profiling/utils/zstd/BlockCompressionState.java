package com.datadog.profiling.utils.zstd;

import java.util.Arrays;

/** Two int[] hash tables for DoubleFast match finder, with window sliding support. */
final class BlockCompressionState {
  final int[] hashTable;
  final int[] chainTable;

  private final long baseAddress;

  // starting point of the window with respect to baseAddress
  private int windowBaseOffset;

  BlockCompressionState(CompressionParameters parameters, long baseAddress) {
    this.baseAddress = baseAddress;
    hashTable = new int[1 << parameters.getHashLog()];
    chainTable = new int[1 << parameters.getChainLog()];
  }

  void slideWindow(int slideWindowSize) {
    for (int i = 0; i < hashTable.length; i++) {
      int newValue = hashTable[i] - slideWindowSize;
      // if new value is negative, set it to zero branchless
      newValue = newValue & (~(newValue >> 31));
      hashTable[i] = newValue;
    }
    for (int i = 0; i < chainTable.length; i++) {
      int newValue = chainTable[i] - slideWindowSize;
      newValue = newValue & (~(newValue >> 31));
      chainTable[i] = newValue;
    }
    // Adjust windowBaseOffset to match the slid data positions
    windowBaseOffset = Math.max(0, windowBaseOffset - slideWindowSize);
  }

  void reset() {
    Arrays.fill(hashTable, 0);
    Arrays.fill(chainTable, 0);
  }

  void enforceMaxDistance(long inputLimit, int maxDistance) {
    int distance = (int) (inputLimit - baseAddress);

    int newOffset = distance - maxDistance;
    if (windowBaseOffset < newOffset) {
      windowBaseOffset = newOffset;
    }
  }

  long getBaseAddress() {
    return baseAddress;
  }

  int getWindowBaseOffset() {
    return windowBaseOffset;
  }
}

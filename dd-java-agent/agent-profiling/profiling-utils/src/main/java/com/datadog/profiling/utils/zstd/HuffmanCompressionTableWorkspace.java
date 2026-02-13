package com.datadog.profiling.utils.zstd;

import java.util.Arrays;

/** Pre-allocated workspace for Huffman tree building. */
final class HuffmanCompressionTableWorkspace {
  static final int MAX_SYMBOL_COUNT = 256;
  static final int MAX_TABLE_LOG = 12;

  final NodeTable nodeTable = new NodeTable(2 * MAX_SYMBOL_COUNT - 1);

  final short[] entriesPerRank = new short[MAX_TABLE_LOG + 1];
  final short[] valuesPerRank = new short[MAX_TABLE_LOG + 1];

  // for setMaxHeight
  final int[] rankLast = new int[MAX_TABLE_LOG + 2];

  void reset() {
    Arrays.fill(entriesPerRank, (short) 0);
    Arrays.fill(valuesPerRank, (short) 0);
  }
}

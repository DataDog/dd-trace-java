package com.datadog.profiling.utils.zstd;

/** Pre-allocated workspace for writing Huffman table headers. */
final class HuffmanTableWriterWorkspace {
  static final int MAX_SYMBOL = 255;
  static final int MAX_TABLE_LOG = 12;
  static final int MAX_FSE_TABLE_LOG = 6;

  // for encoding weights
  final byte[] weights = new byte[MAX_SYMBOL]; // weight for the last symbol is implicit

  // for compressing weights
  final int[] counts = new int[MAX_TABLE_LOG + 1];
  final short[] normalizedCounts = new short[MAX_TABLE_LOG + 1];
  final FseCompressionTable fseTable = new FseCompressionTable(MAX_FSE_TABLE_LOG, MAX_TABLE_LOG);
}

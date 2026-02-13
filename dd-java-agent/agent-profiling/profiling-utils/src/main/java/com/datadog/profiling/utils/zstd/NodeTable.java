package com.datadog.profiling.utils.zstd;

import java.util.Arrays;

/** Huffman tree node data structure with parallel arrays. */
final class NodeTable {
  int[] count;
  short[] parents;
  int[] symbols;
  byte[] numberOfBits;

  NodeTable(int size) {
    count = new int[size];
    parents = new short[size];
    symbols = new int[size];
    numberOfBits = new byte[size];
  }

  void reset() {
    Arrays.fill(count, 0);
    Arrays.fill(parents, (short) 0);
    Arrays.fill(symbols, 0);
    Arrays.fill(numberOfBits, (byte) 0);
  }

  void copyNode(int from, int to) {
    count[to] = count[from];
    parents[to] = parents[from];
    symbols[to] = symbols[from];
    numberOfBits[to] = numberOfBits[from];
  }
}

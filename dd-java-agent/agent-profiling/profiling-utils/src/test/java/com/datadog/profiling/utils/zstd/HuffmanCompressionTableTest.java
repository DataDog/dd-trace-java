package com.datadog.profiling.utils.zstd;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.util.UnsafeUtils;
import org.junit.jupiter.api.Test;

class HuffmanCompressionTableTest {

  @Test
  void optimalNumberOfBitsInputSizeOne() {
    assertThrows(
        IllegalArgumentException.class,
        () -> HuffmanCompressionTable.optimalNumberOfBits(11, 1, 255));
  }

  @Test
  void optimalNumberOfBitsSmallInput() {
    int bits = HuffmanCompressionTable.optimalNumberOfBits(11, 64, 20);
    assertTrue(bits >= 5 && bits <= 11);
  }

  @Test
  void optimalNumberOfBitsLargeInput() {
    int bits = HuffmanCompressionTable.optimalNumberOfBits(11, 100000, 255);
    assertTrue(bits >= 5 && bits <= 11);
  }

  @Test
  void initializeAndEncodeSymbol() {
    // Build a table from a simple distribution
    int[] counts = new int[256];
    counts['a'] = 100;
    counts['b'] = 50;
    counts['c'] = 25;
    counts['d'] = 10;
    counts['e'] = 5;

    HuffmanCompressionTable table = new HuffmanCompressionTable(256);
    HuffmanCompressionTableWorkspace workspace = new HuffmanCompressionTableWorkspace();

    int maxSymbol = 'e';
    int maxBits = HuffmanCompressionTable.optimalNumberOfBits(11, 190, maxSymbol);
    table.initialize(counts, maxSymbol, maxBits, workspace);

    // Encode some symbols — should not throw
    byte[] output = new byte[64];
    BitOutputStream stream = new BitOutputStream(output, UnsafeUtils.BYTE_ARRAY_BASE_OFFSET, 64);
    table.encodeSymbol(stream, 'a');
    table.encodeSymbol(stream, 'b');
    table.encodeSymbol(stream, 'c');
    stream.close();
  }

  @Test
  void writeWithFseCompressedWeights() {
    // Build a table with enough symbols to trigger FSE-compressed weight writing
    int[] counts = new int[256];
    int total = 0;
    for (int i = 0; i < 100; i++) {
      counts[i] = 10 + (i % 20);
      total += counts[i];
    }

    HuffmanCompressionTable table = new HuffmanCompressionTable(256);
    HuffmanCompressionTableWorkspace workspace = new HuffmanCompressionTableWorkspace();

    int maxSymbol = 99;
    int maxBits = HuffmanCompressionTable.optimalNumberOfBits(11, total, maxSymbol);
    table.initialize(counts, maxSymbol, maxBits, workspace);

    byte[] output = new byte[512];
    HuffmanTableWriterWorkspace writerWorkspace = new HuffmanTableWriterWorkspace();
    int size = table.write(output, UnsafeUtils.BYTE_ARRAY_BASE_OFFSET, 512, writerWorkspace);
    assertTrue(size > 0);
  }

  @Test
  void writeWithRawWeightsFallback() {
    // Minimal symbols: raw encoding more efficient than FSE
    int[] counts = new int[256];
    counts[0] = 100;
    counts[1] = 50;
    counts[2] = 1;

    HuffmanCompressionTable table = new HuffmanCompressionTable(256);
    HuffmanCompressionTableWorkspace workspace = new HuffmanCompressionTableWorkspace();

    int maxSymbol = 2;
    int maxBits = HuffmanCompressionTable.optimalNumberOfBits(11, 151, maxSymbol);
    table.initialize(counts, maxSymbol, maxBits, workspace);

    byte[] output = new byte[256];
    HuffmanTableWriterWorkspace writerWorkspace = new HuffmanTableWriterWorkspace();
    int size = table.write(output, UnsafeUtils.BYTE_ARRAY_BASE_OFFSET, 256, writerWorkspace);
    assertTrue(size > 0);
  }

  @Test
  void isValidReturnsFalseForNewSymbol() {
    int[] counts = new int[256];
    counts['a'] = 100;
    counts['b'] = 50;

    HuffmanCompressionTable table = new HuffmanCompressionTable(256);
    HuffmanCompressionTableWorkspace workspace = new HuffmanCompressionTableWorkspace();
    table.initialize(
        counts, 'b', HuffmanCompressionTable.optimalNumberOfBits(11, 150, 'b'), workspace);

    // Now check with a count that has a symbol beyond maxSymbol
    int[] newCounts = new int[256];
    newCounts['a'] = 50;
    newCounts['z'] = 50; // 'z' > 'b' → invalid
    assertTrue(!table.isValid(newCounts, 'z'));
  }

  @Test
  void isValidReturnsFalseForUncoveredSymbol() {
    int[] counts = new int[256];
    counts['a'] = 100;
    counts['c'] = 50; // 'b' has count 0

    HuffmanCompressionTable table = new HuffmanCompressionTable(256);
    HuffmanCompressionTableWorkspace workspace = new HuffmanCompressionTableWorkspace();
    table.initialize(
        counts, 'c', HuffmanCompressionTable.optimalNumberOfBits(11, 150, 'c'), workspace);

    // newCounts has 'b' with non-zero count, but table has 0 bits for 'b'
    int[] newCounts = new int[256];
    newCounts['a'] = 50;
    newCounts['b'] = 50; // table has no code for 'b'
    assertTrue(!table.isValid(newCounts, 'c'));
  }

  @Test
  void estimateCompressedSize() {
    int[] counts = new int[256];
    counts['a'] = 100;
    counts['b'] = 50;
    counts['c'] = 25;

    HuffmanCompressionTable table = new HuffmanCompressionTable(256);
    HuffmanCompressionTableWorkspace workspace = new HuffmanCompressionTableWorkspace();
    table.initialize(
        counts, 'c', HuffmanCompressionTable.optimalNumberOfBits(11, 175, 'c'), workspace);

    int estimate = table.estimateCompressedSize(counts, 'c');
    assertTrue(estimate > 0);
    assertTrue(estimate < 175); // should be smaller than raw size
  }

  @Test
  void initializeWithManySymbolsTriggersSetMaxHeight() {
    // Many symbols with extreme frequency skew → tree height exceeds max
    int[] counts = new int[256];
    // One very frequent symbol, rest very rare
    counts[0] = 1000000;
    for (int i = 1; i < 256; i++) {
      counts[i] = 1;
    }

    HuffmanCompressionTable table = new HuffmanCompressionTable(256);
    HuffmanCompressionTableWorkspace workspace = new HuffmanCompressionTableWorkspace();
    // Use a low maxBits to force setMaxHeight
    table.initialize(counts, 255, 8, workspace);

    // Should succeed (setMaxHeight clamps the tree)
    byte[] output = new byte[64];
    BitOutputStream stream = new BitOutputStream(output, UnsafeUtils.BYTE_ARRAY_BASE_OFFSET, 64);
    table.encodeSymbol(stream, 0);
    table.encodeSymbol(stream, 255);
    stream.close();
  }

  @Test
  void initializeWithModerateSkewTriggersSetMaxHeight() {
    // Several dominant symbols plus rare ones. Fewer rare symbols to avoid degenerate cases.
    int[] counts = new int[256];
    counts[0] = 50000;
    counts[1] = 20000;
    counts[2] = 10000;
    for (int i = 3; i < 50; i++) {
      counts[i] = 1;
    }

    HuffmanCompressionTable table = new HuffmanCompressionTable(256);
    HuffmanCompressionTableWorkspace workspace = new HuffmanCompressionTableWorkspace();
    // maxBits=8 forces setMaxHeight but stays within safe bounds
    table.initialize(counts, 49, 8, workspace);

    byte[] output = new byte[128];
    BitOutputStream stream = new BitOutputStream(output, UnsafeUtils.BYTE_ARRAY_BASE_OFFSET, 128);
    table.encodeSymbol(stream, 0);
    table.encodeSymbol(stream, 25);
    table.encodeSymbol(stream, 49);
    stream.close();
  }

  @Test
  void initializeWithGentleSkew() {
    // Gentle exponential decay: tree depth needs slight clamping
    int[] counts = new int[256];
    for (int i = 0; i < 128; i++) {
      counts[i] = 1000 >> (i / 10); // decays but not too fast
      if (counts[i] == 0) counts[i] = 1;
    }

    HuffmanCompressionTable table = new HuffmanCompressionTable(256);
    HuffmanCompressionTableWorkspace workspace = new HuffmanCompressionTableWorkspace();
    table.initialize(counts, 127, 8, workspace);

    byte[] output = new byte[128];
    BitOutputStream stream = new BitOutputStream(output, UnsafeUtils.BYTE_ARRAY_BASE_OFFSET, 128);
    table.encodeSymbol(stream, 0);
    table.encodeSymbol(stream, 64);
    stream.close();
  }

  @Test
  void writeWithSingleWeight() {
    // weightsLength <= 1 → special case in compressWeights
    int[] counts = new int[256];
    counts[0] = 100;
    counts[1] = 100;

    HuffmanCompressionTable table = new HuffmanCompressionTable(256);
    HuffmanCompressionTableWorkspace workspace = new HuffmanCompressionTableWorkspace();
    table.initialize(counts, 1, 11, workspace);

    byte[] output = new byte[256];
    HuffmanTableWriterWorkspace writerWorkspace = new HuffmanTableWriterWorkspace();
    int size = table.write(output, UnsafeUtils.BYTE_ARRAY_BASE_OFFSET, 256, writerWorkspace);
    assertTrue(size > 0);
  }

  @Test
  void writeAllSameWeights() {
    // All symbols with same weight → maxCount == weightsLength → raw fallback
    int[] counts = new int[256];
    for (int i = 0; i < 8; i++) {
      counts[i] = 100; // all equal
    }

    HuffmanCompressionTable table = new HuffmanCompressionTable(256);
    HuffmanCompressionTableWorkspace workspace = new HuffmanCompressionTableWorkspace();
    table.initialize(counts, 7, 11, workspace);

    byte[] output = new byte[256];
    HuffmanTableWriterWorkspace writerWorkspace = new HuffmanTableWriterWorkspace();
    int size = table.write(output, UnsafeUtils.BYTE_ARRAY_BASE_OFFSET, 256, writerWorkspace);
    assertTrue(size > 0);
  }

  @Test
  void isValidReturnsTrueForMatchingDistribution() {
    int[] counts = new int[256];
    counts['a'] = 100;
    counts['b'] = 50;
    counts['c'] = 25;

    HuffmanCompressionTable table = new HuffmanCompressionTable(256);
    HuffmanCompressionTableWorkspace workspace = new HuffmanCompressionTableWorkspace();
    table.initialize(
        counts, 'c', HuffmanCompressionTable.optimalNumberOfBits(11, 175, 'c'), workspace);

    // Same distribution should be valid for reuse
    assertTrue(table.isValid(counts, 'c'));
  }

  @Test
  void initializeWithDeepTreeAndVeryLowMaxBits() {
    // 64 symbols of frequency 1 plus one dominant symbol. maxBits=6 forces very aggressive
    // tree height reduction in setMaxHeight, exercising the negative-totalCost repayment path.
    int[] counts = new int[256];
    for (int i = 0; i < 64; i++) {
      counts[i] = 1;
    }
    counts[0] = 500;

    HuffmanCompressionTable table = new HuffmanCompressionTable(256);
    HuffmanCompressionTableWorkspace workspace = new HuffmanCompressionTableWorkspace();
    table.initialize(counts, 63, 6, workspace);

    byte[] output = new byte[64];
    BitOutputStream stream = new BitOutputStream(output, UnsafeUtils.BYTE_ARRAY_BASE_OFFSET, 64);
    table.encodeSymbol(stream, 0);
    table.encodeSymbol(stream, 63);
    stream.close();
  }

  @Test
  void writeWithManySymbolsFseCompression() {
    // 200 symbols → exercises compressWeights path with sufficient entries for FSE
    int[] counts = new int[256];
    int total = 0;
    for (int i = 0; i < 200; i++) {
      counts[i] = 5 + (i % 30);
      total += counts[i];
    }

    HuffmanCompressionTable table = new HuffmanCompressionTable(256);
    HuffmanCompressionTableWorkspace workspace = new HuffmanCompressionTableWorkspace();
    table.initialize(
        counts, 199, HuffmanCompressionTable.optimalNumberOfBits(11, total, 199), workspace);

    byte[] output = new byte[512];
    HuffmanTableWriterWorkspace writerWorkspace = new HuffmanTableWriterWorkspace();
    int size = table.write(output, UnsafeUtils.BYTE_ARRAY_BASE_OFFSET, 512, writerWorkspace);
    assertTrue(size > 0);
  }

  @Test
  void initializeUniformDistribution() {
    // All 128 symbols with equal frequency → max tree depth → heavy setMaxHeight work
    int[] counts = new int[256];
    for (int i = 0; i < 128; i++) {
      counts[i] = 10;
    }

    HuffmanCompressionTable table = new HuffmanCompressionTable(256);
    HuffmanCompressionTableWorkspace workspace = new HuffmanCompressionTableWorkspace();
    table.initialize(counts, 127, 8, workspace);

    byte[] output = new byte[256];
    HuffmanTableWriterWorkspace writerWorkspace = new HuffmanTableWriterWorkspace();
    int size = table.write(output, UnsafeUtils.BYTE_ARRAY_BASE_OFFSET, 256, writerWorkspace);
    assertTrue(size > 0);
  }
}

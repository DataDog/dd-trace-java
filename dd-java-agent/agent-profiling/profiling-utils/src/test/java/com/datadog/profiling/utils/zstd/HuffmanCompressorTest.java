package com.datadog.profiling.utils.zstd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.util.UnsafeUtils;
import org.junit.jupiter.api.Test;

class HuffmanCompressorTest {

  private static final long BASE = UnsafeUtils.BYTE_ARRAY_BASE_OFFSET;

  private HuffmanCompressionTable buildTable(byte[] input) {
    int[] counts = new int[256];
    Histogram.count(input, input.length, counts);
    int maxSymbol = Histogram.findMaxSymbol(counts, 255);

    HuffmanCompressionTable table = new HuffmanCompressionTable(256);
    HuffmanCompressionTableWorkspace workspace = new HuffmanCompressionTableWorkspace();
    int maxBits = HuffmanCompressionTable.optimalNumberOfBits(11, input.length, maxSymbol);
    table.initialize(counts, maxSymbol, maxBits, workspace);
    return table;
  }

  @Test
  void compressSingleStreamBasic() {
    byte[] input = new byte[200];
    for (int i = 0; i < input.length; i++) {
      input[i] = (byte) ('a' + (i % 10));
    }

    HuffmanCompressionTable table = buildTable(input);

    byte[] output = new byte[512];
    int size =
        HuffmanCompressor.compressSingleStream(
            output, BASE, output.length, input, BASE, input.length, table);
    assertTrue(size > 0, "Single stream compression should succeed");
  }

  @Test
  void compressSingleStreamTinyOutput() {
    byte[] input = new byte[100];
    for (int i = 0; i < input.length; i++) {
      input[i] = (byte) ('a' + (i % 5));
    }

    HuffmanCompressionTable table = buildTable(input);

    byte[] output = new byte[4]; // smaller than SIZE_OF_LONG → returns 0
    int size =
        HuffmanCompressor.compressSingleStream(
            output, BASE, output.length, input, BASE, input.length, table);
    assertEquals(0, size, "Should return 0 for tiny output buffer");
  }

  @Test
  void compress4streamsBasic() {
    byte[] input = new byte[1024];
    for (int i = 0; i < input.length; i++) {
      input[i] = (byte) ('a' + (i % 15));
    }

    HuffmanCompressionTable table = buildTable(input);

    byte[] output = new byte[2048];
    int size =
        HuffmanCompressor.compress4streams(
            output, BASE, output.length, input, BASE, input.length, table);
    assertTrue(size > 0, "4-stream compression should succeed");
  }

  @Test
  void compress4streamsTinyOutput() {
    byte[] input = new byte[1024];
    for (int i = 0; i < input.length; i++) {
      input[i] = (byte) ('a' + (i % 10));
    }

    HuffmanCompressionTable table = buildTable(input);

    byte[] output = new byte[10]; // less than 6+1+1+1+8=17 → returns 0
    int size =
        HuffmanCompressor.compress4streams(
            output, BASE, output.length, input, BASE, input.length, table);
    assertEquals(0, size, "Should return 0 for tiny output buffer");
  }

  @Test
  void compress4streamsTinyInput() {
    byte[] input = new byte[8]; // <= 6+1+1+1=9 → returns 0
    for (int i = 0; i < input.length; i++) {
      input[i] = (byte) ('a' + i);
    }

    HuffmanCompressionTable table = buildTable(input);

    byte[] output = new byte[256];
    int size =
        HuffmanCompressor.compress4streams(
            output, BASE, output.length, input, BASE, input.length, table);
    assertEquals(0, size, "Should return 0 for tiny input");
  }

  @Test
  void compressSingleStreamInputMod3() {
    // inputSize & 3 == 3 → 3 symbols handled before main loop
    byte[] input = new byte[203]; // 203 & 3 = 3
    for (int i = 0; i < input.length; i++) {
      input[i] = (byte) ('a' + (i % 8));
    }

    HuffmanCompressionTable table = buildTable(input);

    byte[] output = new byte[512];
    int size =
        HuffmanCompressor.compressSingleStream(
            output, BASE, output.length, input, BASE, input.length, table);
    assertTrue(size > 0);
  }

  @Test
  void compressSingleStreamInputMod2() {
    // inputSize & 3 == 2 → 2 symbols handled before main loop
    byte[] input = new byte[202]; // 202 & 3 = 2
    for (int i = 0; i < input.length; i++) {
      input[i] = (byte) ('a' + (i % 8));
    }

    HuffmanCompressionTable table = buildTable(input);

    byte[] output = new byte[512];
    int size =
        HuffmanCompressor.compressSingleStream(
            output, BASE, output.length, input, BASE, input.length, table);
    assertTrue(size > 0);
  }

  @Test
  void compressSingleStreamInputMod1() {
    // inputSize & 3 == 1 → 1 symbol handled before main loop
    byte[] input = new byte[201]; // 201 & 3 = 1
    for (int i = 0; i < input.length; i++) {
      input[i] = (byte) ('a' + (i % 8));
    }

    HuffmanCompressionTable table = buildTable(input);

    byte[] output = new byte[512];
    int size =
        HuffmanCompressor.compressSingleStream(
            output, BASE, output.length, input, BASE, input.length, table);
    assertTrue(size > 0);
  }

  @Test
  void compressSingleStreamInputMod0() {
    // inputSize & 3 == 0 → straight into main loop
    byte[] input = new byte[200]; // 200 & 3 = 0
    for (int i = 0; i < input.length; i++) {
      input[i] = (byte) ('a' + (i % 8));
    }

    HuffmanCompressionTable table = buildTable(input);

    byte[] output = new byte[512];
    int size =
        HuffmanCompressor.compressSingleStream(
            output, BASE, output.length, input, BASE, input.length, table);
    assertTrue(size > 0);
  }
}

package com.datadog.profiling.utils.zstd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.util.UnsafeUtils;
import java.util.Random;
import org.junit.jupiter.api.Test;

class ZstdFrameCompressorTest {

  private static final long BASE = UnsafeUtils.BYTE_ARRAY_BASE_OFFSET;

  @Test
  void writeMagic() {
    byte[] output = new byte[8];
    int size = ZstdFrameCompressor.writeMagic(output, BASE, BASE + 8);
    assertEquals(4, size);
    assertEquals((byte) 0x28, output[0]);
    assertEquals((byte) 0xB5, output[1]);
    assertEquals((byte) 0x2F, output[2]);
    assertEquals((byte) 0xFD, output[3]);
  }

  @Test
  void writeFrameHeaderSmallInput() {
    // inputSize < 256 → contentSizeDescriptor = 0, singleSegment
    byte[] output = new byte[32];
    int size = ZstdFrameCompressor.writeFrameHeader(output, BASE, BASE + 32, 100, 1 << 20);
    assertTrue(size >= 2, "Header should include descriptor + content size byte");
  }

  @Test
  void writeFrameHeaderMediumInput() {
    // 256 <= inputSize < 65792 → contentSizeDescriptor = 1
    byte[] output = new byte[32];
    int size = ZstdFrameCompressor.writeFrameHeader(output, BASE, BASE + 32, 1000, 1 << 20);
    assertTrue(size >= 3, "Header should include descriptor + 2-byte content size");
  }

  @Test
  void writeFrameHeaderLargeInput() {
    // inputSize >= 65792 → contentSizeDescriptor = 2
    byte[] output = new byte[32];
    int size = ZstdFrameCompressor.writeFrameHeader(output, BASE, BASE + 32, 100000, 1 << 20);
    assertTrue(size >= 5, "Header should include descriptor + 4-byte content size");
  }

  @Test
  void writeFrameHeaderStreamingMode() {
    // inputSize = -1 → no content size, not singleSegment, writes window descriptor
    byte[] output = new byte[32];
    int size = ZstdFrameCompressor.writeFrameHeader(output, BASE, BASE + 32, -1, 1 << 17);
    assertTrue(size >= 2, "Header should include descriptor + window byte");
  }

  @Test
  void writeFrameHeaderNonSingleSegmentWritesWindowDescriptor() {
    // inputSize > windowSize → not singleSegment
    byte[] output = new byte[32];
    int size =
        ZstdFrameCompressor.writeFrameHeader(output, BASE, BASE + 32, 2 * 1024 * 1024, 1 << 17);
    assertTrue(size >= 2);
  }

  @Test
  void writeChecksum() {
    byte[] output = new byte[8];
    byte[] input = "hello".getBytes();
    int size =
        ZstdFrameCompressor.writeChecksum(
            output,
            BASE,
            BASE + 8,
            input,
            UnsafeUtils.BYTE_ARRAY_BASE_OFFSET,
            UnsafeUtils.BYTE_ARRAY_BASE_OFFSET + input.length);
    assertEquals(4, size);
  }

  @Test
  void writeCompressedBlockRaw() {
    // Very small or random block → not compressible → raw block
    byte[] input = new byte[32];
    for (int i = 0; i < input.length; i++) {
      input[i] = (byte) (i * 37);
    }

    CompressionParameters params = CompressionParameters.compute(3, -1);
    CompressionContext context = new CompressionContext(params, BASE, Integer.MAX_VALUE);

    byte[] output = new byte[256];
    int size =
        ZstdFrameCompressor.writeCompressedBlock(
            input, BASE, input.length, output, BASE, output.length, context, true);
    assertTrue(size > 0, "Should produce a raw block");
    // Check block header: raw block type (bits [2:1] = 00)
    int blockHeader = (output[0] & 0xFF) | ((output[1] & 0xFF) << 8) | ((output[2] & 0xFF) << 16);
    int blockType = (blockHeader >> 1) & 0x3;
    assertEquals(
        ZstdConstants.RAW_BLOCK, blockType, "Should be a raw block for small incompressible data");
  }

  @Test
  void writeCompressedBlockCompressed() {
    // Compressible data → compressed block
    byte[] input = new byte[4096];
    for (int i = 0; i < input.length; i++) {
      input[i] = (byte) (i % 37);
    }

    CompressionParameters params = CompressionParameters.compute(3, -1);
    CompressionContext context = new CompressionContext(params, BASE, Integer.MAX_VALUE);

    byte[] output = new byte[8192];
    int size =
        ZstdFrameCompressor.writeCompressedBlock(
            input, BASE, input.length, output, BASE, output.length, context, true);
    assertTrue(size > 0);
    assertTrue(size < input.length, "Compressed block should be smaller than input");
  }

  @Test
  void writeCompressedBlockEmptyBlock() {
    CompressionParameters params = CompressionParameters.compute(3, -1);
    CompressionContext context = new CompressionContext(params, BASE, Integer.MAX_VALUE);

    byte[] output = new byte[256];
    int size =
        ZstdFrameCompressor.writeCompressedBlock(
            new byte[0], BASE, 0, output, BASE, output.length, context, true);
    // blockSize=0 → compressedSize=0 → raw block with 0 bytes
    assertEquals(3, size, "Empty block should be just the 3-byte header");
  }

  @Test
  void encodeLiteralsRlePath() {
    // All literals are the same byte and literalsSize > MINIMUM_LITERALS_SIZE (63)
    // → triggers rleLiterals path (largestCount == literalsSize)
    byte[] literals = new byte[200];
    java.util.Arrays.fill(literals, (byte) 'X');

    CompressionParameters params = CompressionParameters.compute(3, -1);
    HuffmanCompressionContext huffCtx = new HuffmanCompressionContext();

    byte[] output = new byte[512];
    int size =
        ZstdFrameCompressor.encodeLiterals(
            huffCtx, params, output, BASE, output.length, literals, literals.length);
    assertTrue(size > 0, "RLE literals should produce output");
    // RLE header + 1 byte of literal = very small output
    assertTrue(size <= 5, "RLE literals should be very compact");
  }

  @Test
  void encodeLiteralsRawPathLowEntropy() {
    // Few distinct bytes with largestCount close to but not equal to literalsSize
    // and largestCount <= literalsSize >>> 7 + 4 → rawLiterals
    // For 128 bytes: threshold = 128/128 + 4 = 5. Need largestCount <= 5 with 128 bytes.
    // That's hard to hit. Instead, use literalsSize <= MINIMUM_LITERALS_SIZE (63) directly.
    byte[] literals = new byte[50]; // <= 63 → directly goes to rawLiterals
    for (int i = 0; i < literals.length; i++) {
      literals[i] = (byte) ('a' + (i % 10));
    }

    CompressionParameters params = CompressionParameters.compute(3, -1);
    HuffmanCompressionContext huffCtx = new HuffmanCompressionContext();

    byte[] output = new byte[256];
    int size =
        ZstdFrameCompressor.encodeLiterals(
            huffCtx, params, output, BASE, output.length, literals, literals.length);
    assertTrue(size > 0, "Raw literals should produce output");
  }

  @Test
  void encodeLiteralsSingleStreamPath() {
    // literalsSize < 256 → singleStream Huffman encoding
    byte[] literals = new byte[200];
    for (int i = 0; i < literals.length; i++) {
      literals[i] = (byte) ('a' + (i % 10));
    }

    CompressionParameters params = CompressionParameters.compute(3, -1);
    HuffmanCompressionContext huffCtx = new HuffmanCompressionContext();

    byte[] output = new byte[1024];
    int size =
        ZstdFrameCompressor.encodeLiterals(
            huffCtx, params, output, BASE, output.length, literals, literals.length);
    assertTrue(size > 0, "Single-stream Huffman should produce output");
  }

  @Test
  void encodeLiterals4StreamPath() {
    // literalsSize >= 256 → 4-stream Huffman encoding
    byte[] literals = new byte[1024];
    for (int i = 0; i < literals.length; i++) {
      literals[i] = (byte) ('a' + (i % 15));
    }

    CompressionParameters params = CompressionParameters.compute(3, -1);
    HuffmanCompressionContext huffCtx = new HuffmanCompressionContext();

    byte[] output = new byte[4096];
    int size =
        ZstdFrameCompressor.encodeLiterals(
            huffCtx, params, output, BASE, output.length, literals, literals.length);
    assertTrue(size > 0, "4-stream Huffman should produce output");
  }

  @Test
  void encodeLiteralsLargeTriggersHeaderSize4() {
    // literalsSize >= 1024 → headerSize = 4
    byte[] literals = new byte[2048];
    for (int i = 0; i < literals.length; i++) {
      literals[i] = (byte) ('a' + (i % 20));
    }

    CompressionParameters params = CompressionParameters.compute(3, -1);
    HuffmanCompressionContext huffCtx = new HuffmanCompressionContext();

    byte[] output = new byte[8192];
    int size =
        ZstdFrameCompressor.encodeLiterals(
            huffCtx, params, output, BASE, output.length, literals, literals.length);
    assertTrue(size > 0);
  }

  @Test
  void encodeLiteralsRleSmallSize() {
    // RLE with literalsSize <= 31 → headerSize = 1
    byte[] literals = new byte[20];
    java.util.Arrays.fill(literals, (byte) 'Z');
    // literalsSize=20 <= 63 → rawLiterals, BUT largestCount == literalsSize
    // Wait: literalsSize <= MINIMUM_LITERALS_SIZE (63) is checked FIRST (line 249)
    // So this goes to rawLiterals, not rleLiterals. Need > 63.

    // Test with 64 bytes all same → rleLiterals with size 64 > 31 → headerSize = 2
    literals = new byte[64];
    java.util.Arrays.fill(literals, (byte) 'Z');

    CompressionParameters params = CompressionParameters.compute(3, -1);
    HuffmanCompressionContext huffCtx = new HuffmanCompressionContext();

    byte[] output = new byte[256];
    int size =
        ZstdFrameCompressor.encodeLiterals(
            huffCtx, params, output, BASE, output.length, literals, literals.length);
    assertTrue(size > 0 && size <= 4);
  }

  @Test
  void encodeLiteralsRleLargeSize() {
    // RLE with literalsSize > 4095 → headerSize = 3
    byte[] literals = new byte[5000];
    java.util.Arrays.fill(literals, (byte) 'A');

    CompressionParameters params = CompressionParameters.compute(3, -1);
    HuffmanCompressionContext huffCtx = new HuffmanCompressionContext();

    byte[] output = new byte[256];
    int size =
        ZstdFrameCompressor.encodeLiterals(
            huffCtx, params, output, BASE, output.length, literals, literals.length);
    assertTrue(size > 0 && size <= 5);
  }

  @Test
  void encodeLiteralsTableReuse() {
    // Encode twice with similar distributions → second call may reuse table (TREELESS)
    CompressionParameters params = CompressionParameters.compute(3, -1);
    HuffmanCompressionContext huffCtx = new HuffmanCompressionContext();

    byte[] literals1 = new byte[200];
    for (int i = 0; i < literals1.length; i++) {
      literals1[i] = (byte) ('a' + (i % 10));
    }

    byte[] output = new byte[1024];
    int size1 =
        ZstdFrameCompressor.encodeLiterals(
            huffCtx, params, output, BASE, output.length, literals1, literals1.length);
    assertTrue(size1 > 0);
    // Commit to "save" the table
    huffCtx.saveChanges();

    // Second call with similar distribution → preferReuse may trigger TREELESS path
    byte[] literals2 = new byte[200];
    for (int i = 0; i < literals2.length; i++) {
      literals2[i] = (byte) ('a' + (i % 10));
    }
    int size2 =
        ZstdFrameCompressor.encodeLiterals(
            huffCtx, params, output, BASE, output.length, literals2, literals2.length);
    assertTrue(size2 > 0);
  }

  @Test
  void writeFrameHeaderContentSize65535() {
    // inputSize = 65535 → contentSizeDescriptor = 1 (256 <= 65535 < 65792)
    byte[] output = new byte[32];
    int size = ZstdFrameCompressor.writeFrameHeader(output, BASE, BASE + 32, 65535, 1 << 20);
    assertTrue(size >= 3);
  }

  @Test
  void writeFrameHeaderContentSize65792() {
    // inputSize = 65792 → contentSizeDescriptor = 2 (exact boundary)
    byte[] output = new byte[32];
    int size = ZstdFrameCompressor.writeFrameHeader(output, BASE, BASE + 32, 65792, 1 << 20);
    assertTrue(size >= 5);
  }

  @Test
  void writeCompressedBlockNotLastBlock() {
    byte[] input = new byte[4096];
    for (int i = 0; i < input.length; i++) {
      input[i] = (byte) (i % 37);
    }

    CompressionParameters params = CompressionParameters.compute(3, -1);
    CompressionContext context = new CompressionContext(params, BASE, Integer.MAX_VALUE);

    byte[] output = new byte[8192];
    int size =
        ZstdFrameCompressor.writeCompressedBlock(
            input, BASE, input.length, output, BASE, output.length, context, false);
    assertTrue(size > 0);
    // Check lastBlock bit is 0
    int blockHeader = (output[0] & 0xFF) | ((output[1] & 0xFF) << 8) | ((output[2] & 0xFF) << 16);
    assertEquals(0, blockHeader & 1, "lastBlock bit should be 0");
  }

  @Test
  void encodeLiteralsCompressionFailsFallsBackToRaw() {
    // Random data → Huffman can't compress → falls back to rawLiterals
    // Use >= 4096 bytes to exercise rawLiterals headerSize=3 branch
    byte[] literals = new byte[5000];
    new Random(42).nextBytes(literals);

    CompressionParameters params = CompressionParameters.compute(3, -1);
    HuffmanCompressionContext huffCtx = new HuffmanCompressionContext();

    byte[] output = new byte[8192];
    int size =
        ZstdFrameCompressor.encodeLiterals(
            huffCtx, params, output, BASE, output.length, literals, literals.length);
    // Should fall back to raw, so size ≈ literals.length + headerSize
    assertTrue(size >= literals.length, "Raw fallback should be at least input size");
  }

  @Test
  void encodeLiteralsVeryLargeTriggersHeaderSize5() {
    // literalsSize >= 16384 → headerSize = 5 in compressed literals header
    byte[] literals = new byte[20000];
    for (int i = 0; i < literals.length; i++) {
      literals[i] = (byte) ('a' + (i % 10));
    }

    CompressionParameters params = CompressionParameters.compute(3, -1);
    HuffmanCompressionContext huffCtx = new HuffmanCompressionContext();

    byte[] output = new byte[32768];
    int size =
        ZstdFrameCompressor.encodeLiterals(
            huffCtx, params, output, BASE, output.length, literals, literals.length);
    assertTrue(size > 0, "Very large literals should compress");
  }

  @Test
  void encodeLiteralsHighEntropyLargeRawFallback() {
    // Moderately high entropy, enough symbols to bypass RLE and low-entropy checks,
    // but Huffman doesn't achieve enough gain → rawLiterals fallback
    byte[] literals = new byte[512];
    for (int i = 0; i < literals.length; i++) {
      literals[i] = (byte) (i % 200);
    }

    CompressionParameters params = CompressionParameters.compute(3, -1);
    HuffmanCompressionContext huffCtx = new HuffmanCompressionContext();

    byte[] output = new byte[2048];
    int size =
        ZstdFrameCompressor.encodeLiterals(
            huffCtx, params, output, BASE, output.length, literals, literals.length);
    assertTrue(size > 0);
  }

  @Test
  void writeCompressedBlockAllSameData() {
    // All same bytes: should produce RLE block or very small compressed block
    byte[] input = new byte[4096];
    java.util.Arrays.fill(input, (byte) 0x42);

    CompressionParameters params = CompressionParameters.compute(3, -1);
    CompressionContext context = new CompressionContext(params, BASE, Integer.MAX_VALUE);

    byte[] output = new byte[8192];
    int size =
        ZstdFrameCompressor.writeCompressedBlock(
            input, BASE, input.length, output, BASE, output.length, context, true);
    assertTrue(size > 0);
    assertTrue(size < 100, "All-same data should compress very well");
  }

  @Test
  void writeCompressedBlockLargeEnglishLikeData() {
    // Large block with matchable patterns → exercises 4-stream Huffman + sequence encoding
    byte[] input = ZstdRoundTripTest.englishLikeData(128 * 1024, 42);

    CompressionParameters params = CompressionParameters.compute(3, -1);
    CompressionContext context = new CompressionContext(params, BASE, Integer.MAX_VALUE);

    byte[] output = new byte[256 * 1024];
    int size =
        ZstdFrameCompressor.writeCompressedBlock(
            input, BASE, input.length, output, BASE, output.length, context, true);
    assertTrue(size > 0);
    assertTrue(size < input.length, "English-like data should be compressible");
  }

  @Test
  void writeFrameHeaderVeryLargeInput() {
    // inputSize = 2^24 → contentSizeDescriptor = 2, 4-byte content size
    byte[] output = new byte[32];
    int size = ZstdFrameCompressor.writeFrameHeader(output, BASE, BASE + 32, 1 << 24, 1 << 20);
    assertTrue(size >= 5);
  }
}

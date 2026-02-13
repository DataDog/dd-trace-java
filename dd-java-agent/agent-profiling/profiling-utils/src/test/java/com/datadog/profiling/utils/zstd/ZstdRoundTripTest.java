package com.datadog.profiling.utils.zstd;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.airlift.compress.zstd.ZstdInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Round-trip tests: compress with our ZstdOutputStream, decompress with aircompressor
 * ZstdInputStream, verify byte-perfect match.
 */
class ZstdRoundTripTest {

  @ParameterizedTest(name = "{0}")
  @MethodSource("testCases")
  void roundTrip(String name, byte[] input) throws IOException {
    byte[] compressed = compress(input);
    byte[] decompressed = decompress(compressed);
    assertArrayEquals(input, decompressed, "Round-trip failed for: " + name);
  }

  @Test
  void compressedSmallerThanInput() throws IOException {
    byte[] input = new byte[1024 * 1024];
    for (int i = 0; i < input.length; i++) {
      input[i] = (byte) (i % 37);
    }
    byte[] compressed = compress(input);
    assertTrue(
        compressed.length < input.length,
        "Compressed size ("
            + compressed.length
            + ") should be less than input size ("
            + input.length
            + ")");
  }

  @Test
  void streamingWriteSingleBytes() throws IOException {
    byte[] input = new byte[4096];
    new Random(42).nextBytes(input);

    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    try (ZstdOutputStream zos = new ZstdOutputStream(baos)) {
      for (byte b : input) {
        zos.write(b);
      }
    }

    byte[] decompressed = decompress(baos.toByteArray());
    assertArrayEquals(input, decompressed);
  }

  @Test
  void streamingWriteVariousChunkSizes() throws IOException {
    byte[] input = new byte[128 * 1024];
    new Random(99).nextBytes(input);

    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    try (ZstdOutputStream zos = new ZstdOutputStream(baos)) {
      int offset = 0;
      int chunkSize = 1;
      while (offset < input.length) {
        int len = Math.min(chunkSize, input.length - offset);
        zos.write(input, offset, len);
        offset += len;
        chunkSize = Math.min(chunkSize * 2, 32768);
      }
    }

    byte[] decompressed = decompress(baos.toByteArray());
    assertArrayEquals(input, decompressed);
  }

  @Test
  void zstdMagicPresent() throws IOException {
    byte[] compressed = compress(new byte[] {1, 2, 3});
    assertEquals((byte) 0x28, compressed[0]);
    assertEquals((byte) 0xB5, compressed[1]);
    assertEquals((byte) 0x2F, compressed[2]);
    assertEquals((byte) 0xFD, compressed[3]);
  }

  @Test
  void writeByteAfterCloseThrows() throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    ZstdOutputStream zos = new ZstdOutputStream(baos);
    zos.close();
    assertThrows(IOException.class, () -> zos.write(1));
  }

  @Test
  void writeArrayAfterCloseThrows() throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    ZstdOutputStream zos = new ZstdOutputStream(baos);
    zos.close();
    assertThrows(IOException.class, () -> zos.write(new byte[] {1, 2, 3}, 0, 3));
  }

  @Test
  void doubleCloseIsHarmless() throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    ZstdOutputStream zos = new ZstdOutputStream(baos);
    zos.write(new byte[] {42});
    zos.close();
    assertDoesNotThrow(zos::close);
  }

  @Test
  void writeByteArrayOverload() throws IOException {
    byte[] input = "test data for write(byte[]) overload".getBytes();
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    try (ZstdOutputStream zos = new ZstdOutputStream(baos)) {
      zos.write(input);
    }
    byte[] decompressed = decompress(baos.toByteArray());
    assertArrayEquals(input, decompressed);
  }

  @Test
  void largeStreamingDataTriggersWindowSlide() throws IOException {
    // windowSize=1MB at level 3, maxBufferSize=4MB.
    // Write 5MB to force at least one mid-stream flush with window sliding.
    int size = 5 * 1024 * 1024;
    byte[] input = englishLikeData(size, 7);

    byte[] compressed = compress(input);
    byte[] decompressed = decompress(compressed);
    assertArrayEquals(input, decompressed);
  }

  static Stream<org.junit.jupiter.params.provider.Arguments> testCases() {
    return Stream.of(
        testCase("empty", new byte[0]),
        testCase("single byte", new byte[] {42}),
        testCase("two bytes", new byte[] {0, 1}),
        testCase("small text", "Hello, Zstd!".getBytes()),
        testCase("all zeros 1KB", new byte[1024]),
        testCase("all zeros 128KB", new byte[128 * 1024]),
        testCase("all zeros 1MB", new byte[1024 * 1024]),
        testCase("random 1KB", randomBytes(1024, 1)),
        testCase("random 128KB", randomBytes(128 * 1024, 2)),
        testCase("random 1MB", randomBytes(1024 * 1024, 3)),
        testCase("repeating pattern 1MB", repeatingPattern(1024 * 1024)),
        testCase("ascending bytes 1KB", ascendingBytes(1024)),
        testCase("block boundary exact", new byte[128 * 1024]),
        testCase("block boundary plus one", new byte[128 * 1024 + 1]),
        // English-like data with skewed byte distribution — exercises Huffman encoding
        testCase("english-like 256KB", englishLikeData(256 * 1024, 42)),
        // Multi-block compressible data — exercises FSE compressed sequence tables
        testCase("english-like 512KB", englishLikeData(512 * 1024, 99)),
        // Biased data with dominant byte — exercises RLE literals threshold
        testCase("dominant byte 128KB", dominantByteData(128 * 1024, 0x41, 0.85, 5)),
        // Semi-random: half pattern, half biased random — mixed match/literal behavior
        testCase("mixed pattern 256KB", mixedPatternData(256 * 1024, 13)),
        // Medium-sized data (256-65791 bytes) — exercises contentSizeDescriptor=1 in frame header
        testCase("english-like 4KB", englishLikeData(4096, 77)),
        // Dominant byte with high ratio — exercises RLE literals path (all literals same byte)
        testCase("dominant byte 95% 128KB", dominantByteData(128 * 1024, 0x42, 0.95, 8)),
        // Large multi-block with table reuse — exercises TREELESS_LITERALS_BLOCK path
        testCase("english-like 1MB", englishLikeData(1024 * 1024, 123)));
  }

  private static org.junit.jupiter.params.provider.Arguments testCase(
      String name, byte[] data) {
    return org.junit.jupiter.params.provider.Arguments.of(name, data);
  }

  private static byte[] randomBytes(int size, long seed) {
    byte[] data = new byte[size];
    new Random(seed).nextBytes(data);
    return data;
  }

  private static byte[] repeatingPattern(int size) {
    byte[] data = new byte[size];
    for (int i = 0; i < size; i++) {
      data[i] = (byte) (i % 251);
    }
    return data;
  }

  private static byte[] ascendingBytes(int size) {
    byte[] data = new byte[size];
    for (int i = 0; i < size; i++) {
      data[i] = (byte) (i & 0xFF);
    }
    return data;
  }

  /**
   * Generates data with English-like byte distribution. Lowercase letters and spaces dominate,
   * creating a skewed distribution that triggers Huffman encoding in the compressor.
   */
  static byte[] englishLikeData(int size, long seed) {
    // Base phrase provides matchable patterns for the DoubleFast compressor
    String[] phrases = {
      "the quick brown fox jumps over the lazy dog ",
      "pack my box with five dozen liquor jugs ",
      "how vexingly quick daft zebras jump ",
      "the five boxing wizards jump quickly ",
      "sphinx of black quartz judge my vow ",
    };

    byte[] data = new byte[size];
    Random rng = new Random(seed);
    int pos = 0;

    while (pos < size) {
      // Pick a phrase and write it (with occasional character mutations)
      String phrase = phrases[rng.nextInt(phrases.length)];
      for (int i = 0; i < phrase.length() && pos < size; i++) {
        char c = phrase.charAt(i);
        // 5% chance of mutation to keep some non-matchable literals
        if (rng.nextInt(20) == 0) {
          c = (char) ('a' + rng.nextInt(26));
        }
        data[pos++] = (byte) c;
      }
    }
    return data;
  }

  /**
   * Generates data where one byte value dominates at the given ratio. The remaining bytes are
   * drawn from a small alphabet. This tests the boundary between RLE and Huffman encoding.
   */
  private static byte[] dominantByteData(int size, int dominant, double ratio, long seed) {
    byte[] data = new byte[size];
    Random rng = new Random(seed);
    for (int i = 0; i < size; i++) {
      if (rng.nextDouble() < ratio) {
        data[i] = (byte) dominant;
      } else {
        data[i] = (byte) ('a' + rng.nextInt(10));
      }
    }
    return data;
  }

  /**
   * Generates mixed data: alternating segments of a repeating pattern (matchable) and
   * biased-random content (produces varied literals). This forces the compressor to produce
   * both matches and substantial literal runs with varied symbol codes.
   */
  private static byte[] mixedPatternData(int size, long seed) {
    byte[] data = new byte[size];
    Random rng = new Random(seed);
    byte[] pattern = "abcdefghijklmnopqrstuvwxyz0123456789".getBytes();
    int segmentSize = 256;

    for (int pos = 0; pos < size; ) {
      int segLen = Math.min(segmentSize, size - pos);
      if ((pos / segmentSize) % 2 == 0) {
        // Pattern segment: repeating sequence (matchable)
        for (int i = 0; i < segLen; i++) {
          data[pos + i] = pattern[i % pattern.length];
        }
      } else {
        // Random segment with biased distribution (varied literals)
        for (int i = 0; i < segLen; i++) {
          // Biased: 70% lowercase, 20% digits, 10% other
          int r = rng.nextInt(10);
          if (r < 7) {
            data[pos + i] = (byte) ('a' + rng.nextInt(26));
          } else if (r < 9) {
            data[pos + i] = (byte) ('0' + rng.nextInt(10));
          } else {
            data[pos + i] = (byte) (rng.nextInt(128));
          }
        }
      }
      pos += segLen;
    }
    return data;
  }

  static byte[] compress(byte[] input) throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    try (ZstdOutputStream zos = new ZstdOutputStream(baos)) {
      zos.write(input);
    }
    return baos.toByteArray();
  }

  static byte[] decompress(byte[] compressed) throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    try (ZstdInputStream zis = new ZstdInputStream(new ByteArrayInputStream(compressed))) {
      byte[] buf = new byte[8192];
      int n;
      while ((n = zis.read(buf)) >= 0) {
        baos.write(buf, 0, n);
      }
    }
    return baos.toByteArray();
  }
}

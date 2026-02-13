package com.datadog.profiling.utils.zstd;

import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.util.UnsafeUtils;
import org.junit.jupiter.api.Test;

class SequenceEncoderTest {

  private static final long BASE = UnsafeUtils.BYTE_ARRAY_BASE_OFFSET;

  @Test
  void compressSequencesWithRleEncodingTypes() {
    // Create a SequenceStore where all sequences have the same literal length code,
    // same match length code, and same offset code → triggers SEQUENCE_ENCODING_RLE
    // for all three tables
    SequenceStore sequences = new SequenceStore(4096, 256);

    // Add multiple sequences with identical codes
    // All literal lengths = 0 → code 0
    // All match lengths = 3 (base) → code 0
    // All offsets with same high bit
    byte[] dummyLiterals = new byte[4096];
    for (int i = 0; i < 10; i++) {
      sequences.literalLengths[i] = 0;
      sequences.matchLengths[i] = 3;
      sequences.offsets[i] = 4; // highestBit(4) = 2
    }
    sequences.sequenceCount = 10;
    sequences.literalsLength = 0;

    // Generate codes
    sequences.generateCodes();

    // All literal length codes should be 0, all match length codes should be 0
    // All offset codes should be 2
    // With 10 sequences and all same code → largestCount == sequenceCount → RLE
    // But sequenceCount > 2 so it won't fall to BASIC

    byte[] output = new byte[1024];
    SequenceEncodingContext workspace = new SequenceEncodingContext();

    int size =
        SequenceEncoder.compressSequences(
            output,
            BASE,
            output.length,
            sequences,
            CompressionParameters.Strategy.DFAST,
            workspace);
    assertTrue(size > 0, "Should encode sequences with RLE tables");
  }

  @Test
  void compressSequencesWithCompressedEncodingType() {
    // Create sequences with varied codes → triggers SEQUENCE_ENCODING_COMPRESSED
    SequenceStore sequences = new SequenceStore(4096, 256);

    // Varied literal lengths, match lengths, and offsets
    for (int i = 0; i < 50; i++) {
      sequences.literalLengths[i] = i % 20;
      sequences.matchLengths[i] = 3 + (i % 30);
      sequences.offsets[i] = 1 + (i % 16);
    }
    sequences.sequenceCount = 50;
    sequences.literalsLength = 100;

    sequences.generateCodes();

    byte[] output = new byte[4096];
    SequenceEncodingContext workspace = new SequenceEncodingContext();

    int size =
        SequenceEncoder.compressSequences(
            output,
            BASE,
            output.length,
            sequences,
            CompressionParameters.Strategy.DFAST,
            workspace);
    assertTrue(size > 0, "Should encode sequences with compressed tables");
  }

  @Test
  void compressSequencesWithFewSequencesUsesBasic() {
    // Very few sequences with all-same codes and count <= 2 → BASIC encoding
    SequenceStore sequences = new SequenceStore(4096, 256);

    sequences.literalLengths[0] = 5;
    sequences.matchLengths[0] = 4;
    sequences.offsets[0] = 2;
    sequences.sequenceCount = 1;
    sequences.literalsLength = 5;

    sequences.generateCodes();

    byte[] output = new byte[1024];
    SequenceEncodingContext workspace = new SequenceEncodingContext();

    int size =
        SequenceEncoder.compressSequences(
            output,
            BASE,
            output.length,
            sequences,
            CompressionParameters.Strategy.DFAST,
            workspace);
    assertTrue(size > 0, "Single sequence should encode with basic/default tables");
  }

  @Test
  void compressSequencesZeroSequences() {
    SequenceStore sequences = new SequenceStore(4096, 256);
    sequences.sequenceCount = 0;
    sequences.literalsLength = 0;

    byte[] output = new byte[1024];
    SequenceEncodingContext workspace = new SequenceEncodingContext();

    int size =
        SequenceEncoder.compressSequences(
            output,
            BASE,
            output.length,
            sequences,
            CompressionParameters.Strategy.DFAST,
            workspace);
    // sequenceCount == 0 → just writes 1-byte count header and returns
    assertTrue(size == 1);
  }

  @Test
  void compressSequencesMediumCount() {
    // sequenceCount >= 0x7F but < 0x7F00 → 2-byte count encoding
    SequenceStore sequences = new SequenceStore(65536, 256);

    int seqCount = 200; // >= 0x7F (127)
    for (int i = 0; i < seqCount; i++) {
      sequences.literalLengths[i] = i % 10;
      sequences.matchLengths[i] = 3 + (i % 20);
      sequences.offsets[i] = 1 + (i % 8);
    }
    sequences.sequenceCount = seqCount;
    sequences.literalsLength = 500;
    sequences.generateCodes();

    byte[] output = new byte[16384];
    SequenceEncodingContext workspace = new SequenceEncodingContext();

    int size =
        SequenceEncoder.compressSequences(
            output, BASE, output.length, sequences,
            CompressionParameters.Strategy.DFAST, workspace);
    assertTrue(size > 0);
  }

  @Test
  void compressSequencesWithDefaultTableNotAllowed() {
    // Offsets with maxSymbol >= DEFAULT_MAX_OFFSET_CODE_SYMBOL → default table not allowed
    // DEFAULT_MAX_OFFSET_CODE_SYMBOL = 28, so need offset codes > 28
    // offsetCode = highestBit(offset), so need offset >= 2^28 = 268435456
    SequenceStore sequences = new SequenceStore(65536, 256);

    for (int i = 0; i < 30; i++) {
      sequences.literalLengths[i] = i % 5;
      sequences.matchLengths[i] = 3 + (i % 10);
      sequences.offsets[i] = (1 << 29) + i; // highestBit = 29 > 28
    }
    sequences.sequenceCount = 30;
    sequences.literalsLength = 100;
    sequences.generateCodes();

    byte[] output = new byte[8192];
    SequenceEncodingContext workspace = new SequenceEncodingContext();

    int size =
        SequenceEncoder.compressSequences(
            output, BASE, output.length, sequences,
            CompressionParameters.Strategy.DFAST, workspace);
    assertTrue(size > 0);
  }

  @Test
  void compressSequencesWithHighBitCounts() {
    // Sequences with large literal lengths, offsets, and match lengths
    // to trigger the extra flush conditions in encodeSequences
    SequenceStore sequences = new SequenceStore(65536, 256);

    for (int i = 0; i < 20; i++) {
      // Large literal lengths → high literalLengthBits
      sequences.literalLengths[i] = 10000 + i;
      // Large match lengths → high matchLengthBits
      sequences.matchLengths[i] = 1000 + i;
      // Large offsets → high offsetBits
      sequences.offsets[i] = (1 << 20) + i;
    }
    sequences.sequenceCount = 20;
    sequences.literalsLength = 200000;
    sequences.generateCodes();

    byte[] output = new byte[65536];
    SequenceEncodingContext workspace = new SequenceEncodingContext();

    int size =
        SequenceEncoder.compressSequences(
            output, BASE, output.length, sequences,
            CompressionParameters.Strategy.DFAST, workspace);
    assertTrue(size > 0);
  }

  @Test
  void compressSequencesBasicWithNonDefaultAllowed() {
    // Few sequences with non-uniform codes → BASIC encoding because
    // sequenceCount < minNumberOfSequences
    SequenceStore sequences = new SequenceStore(4096, 256);

    sequences.literalLengths[0] = 3;
    sequences.matchLengths[0] = 5;
    sequences.offsets[0] = 8;
    sequences.literalLengths[1] = 10;
    sequences.matchLengths[1] = 7;
    sequences.offsets[1] = 16;
    sequences.sequenceCount = 2;
    sequences.literalsLength = 13;
    sequences.generateCodes();

    byte[] output = new byte[1024];
    SequenceEncodingContext workspace = new SequenceEncodingContext();

    int size =
        SequenceEncoder.compressSequences(
            output, BASE, output.length, sequences,
            CompressionParameters.Strategy.DFAST, workspace);
    assertTrue(size > 0);
  }

  @Test
  void compressSequencesWithExtremeBitWidthsTriggersFlush() {
    // literalLengthBits + matchLengthBits > 24 triggers flush at line 346.
    // literalLength=65536 → code 35 → 16 extra bits
    // matchLength=65536 → code 52 → 16 extra bits → 16+16=32 > 24
    // offsetBits + matchLengthBits + literalLengthBits > 56 triggers flush at line 351.
    // offset=2^25 → 25 bits → 25+16+16=57 > 56
    SequenceStore sequences = new SequenceStore(65536, 256);

    for (int i = 0; i < 10; i++) {
      sequences.literalLengths[i] = 65536 + i;
      sequences.matchLengths[i] = 65536 + i;
      sequences.offsets[i] = (1 << 25) + i;
    }
    sequences.sequenceCount = 10;
    sequences.literalsLength = 655360;

    sequences.generateCodes();

    byte[] output = new byte[65536];
    SequenceEncodingContext workspace = new SequenceEncodingContext();

    int size =
        SequenceEncoder.compressSequences(
            output,
            BASE,
            output.length,
            sequences,
            CompressionParameters.Strategy.DFAST,
            workspace);
    assertTrue(size > 0, "Extreme bit widths should still encode");
  }
}

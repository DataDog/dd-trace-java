package com.datadog.profiling.utils.zstd;

import static com.datadog.profiling.utils.zstd.ZstdConstants.SIZE_OF_LONG;
import static com.datadog.profiling.utils.zstd.ZstdConstants.SIZE_OF_SHORT;

import datadog.trace.util.UnsafeUtils;

/** 4-stream and single-stream Huffman encoding. */
final class HuffmanCompressor {
  private HuffmanCompressor() {}

  static int compress4streams(
      Object outputBase,
      long outputAddress,
      int outputSize,
      Object inputBase,
      long inputAddress,
      int inputSize,
      HuffmanCompressionTable table) {
    long input = inputAddress;
    long inputLimit = inputAddress + inputSize;
    long output = outputAddress;
    long outputLimit = outputAddress + outputSize;

    int segmentSize = (inputSize + 3) / 4;

    if (outputSize < 6 + 1 + 1 + 1 + 8) {
      return 0; // minimum space to compress successfully
    }

    if (inputSize <= 6 + 1 + 1 + 1) {
      return 0; // no saving possible: input too small
    }

    output += SIZE_OF_SHORT + SIZE_OF_SHORT + SIZE_OF_SHORT; // jump table

    int compressedSize;

    // first segment
    compressedSize =
        compressSingleStream(
            outputBase, output, (int) (outputLimit - output), inputBase, input, segmentSize, table);
    if (compressedSize == 0) {
      return 0;
    }
    UnsafeUtils.putShort(outputBase, outputAddress, (short) compressedSize);
    output += compressedSize;
    input += segmentSize;

    // second segment
    compressedSize =
        compressSingleStream(
            outputBase, output, (int) (outputLimit - output), inputBase, input, segmentSize, table);
    if (compressedSize == 0) {
      return 0;
    }
    UnsafeUtils.putShort(outputBase, outputAddress + SIZE_OF_SHORT, (short) compressedSize);
    output += compressedSize;
    input += segmentSize;

    // third segment
    compressedSize =
        compressSingleStream(
            outputBase, output, (int) (outputLimit - output), inputBase, input, segmentSize, table);
    if (compressedSize == 0) {
      return 0;
    }
    UnsafeUtils.putShort(
        outputBase, outputAddress + SIZE_OF_SHORT + SIZE_OF_SHORT, (short) compressedSize);
    output += compressedSize;
    input += segmentSize;

    // fourth segment
    compressedSize =
        compressSingleStream(
            outputBase,
            output,
            (int) (outputLimit - output),
            inputBase,
            input,
            (int) (inputLimit - input),
            table);
    if (compressedSize == 0) {
      return 0;
    }
    output += compressedSize;

    return (int) (output - outputAddress);
  }

  @SuppressWarnings("fallthrough") // Intentional fall-through for Duff's device pattern
  static int compressSingleStream(
      Object outputBase,
      long outputAddress,
      int outputSize,
      Object inputBase,
      long inputAddress,
      int inputSize,
      HuffmanCompressionTable table) {
    if (outputSize < SIZE_OF_LONG) {
      return 0;
    }

    BitOutputStream bitstream = new BitOutputStream(outputBase, outputAddress, outputSize);
    long input = inputAddress;

    int n = inputSize & ~3; // join to mod 4

    switch (inputSize & 3) {
      case 3:
        table.encodeSymbol(bitstream, UnsafeUtils.getByte(inputBase, input + n + 2) & 0xFF);
      // fall through
      case 2:
        table.encodeSymbol(bitstream, UnsafeUtils.getByte(inputBase, input + n + 1) & 0xFF);
      // fall through
      case 1:
        table.encodeSymbol(bitstream, UnsafeUtils.getByte(inputBase, input + n) & 0xFF);
        bitstream.flush();
      // fall through
      case 0:
      default:
        break;
    }

    for (; n > 0; n -= 4) {
      table.encodeSymbol(bitstream, UnsafeUtils.getByte(inputBase, input + n - 1) & 0xFF);
      table.encodeSymbol(bitstream, UnsafeUtils.getByte(inputBase, input + n - 2) & 0xFF);
      table.encodeSymbol(bitstream, UnsafeUtils.getByte(inputBase, input + n - 3) & 0xFF);
      table.encodeSymbol(bitstream, UnsafeUtils.getByte(inputBase, input + n - 4) & 0xFF);
      bitstream.flush();
    }

    return bitstream.close();
  }
}

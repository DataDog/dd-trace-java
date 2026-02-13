package com.datadog.profiling.utils.zstd;

/**
 * Interface for ASM-generated Huffman encoders.
 *
 * <p>Must be public for visibility to dynamically generated classes loaded via AsmClassLoader.
 */
public interface HuffmanEncoder {
  int encode4Streams(
      byte[] output,
      int outputOffset,
      int outputSize,
      byte[] input,
      int inputOffset,
      int inputSize);
}

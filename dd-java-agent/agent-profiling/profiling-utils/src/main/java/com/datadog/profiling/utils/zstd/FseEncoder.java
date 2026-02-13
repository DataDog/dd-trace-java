package com.datadog.profiling.utils.zstd;

/**
 * Interface for ASM-generated FSE (Finite State Entropy) encoders.
 *
 * <p>Must be public for visibility to dynamically generated classes loaded via AsmClassLoader.
 */
public interface FseEncoder {
  int begin(int symbol);

  int encode(BitOutputStream out, int state, int symbol);

  void finish(BitOutputStream out, int state);
}

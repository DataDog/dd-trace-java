package com.datadog.profiling.utils.zstd;

/** Interface for ASM-generated FSE (Finite State Entropy) encoders. */
public interface FseEncoder {
  int begin(int symbol);

  int encode(BitOutputStream out, int state, int symbol);

  void finish(BitOutputStream out, int state);
}

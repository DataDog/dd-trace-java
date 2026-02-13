package com.datadog.profiling.utils.zstd;

interface BlockCompressor {
  int compressBlock(
      Object inputBase,
      long inputAddress,
      int inputSize,
      SequenceStore output,
      BlockCompressionState state,
      RepeatedOffsets offsets,
      CompressionParameters parameters);
}

package com.datadog.profiling.utils.zstd;

import static com.datadog.profiling.utils.zstd.Util.checkArgument;
import static com.datadog.profiling.utils.zstd.ZstdConstants.MAX_BLOCK_SIZE;

/** Aggregates all pre-allocated state for one compression stream. Reused across blocks. */
final class CompressionContext {
  final CompressionParameters parameters;
  final RepeatedOffsets offsets = new RepeatedOffsets();
  final BlockCompressionState blockCompressionState;
  final SequenceStore sequenceStore;

  final SequenceEncodingContext sequenceEncodingContext = new SequenceEncodingContext();

  final HuffmanCompressionContext huffmanContext = new HuffmanCompressionContext();

  CompressionContext(CompressionParameters parameters, long baseAddress, int inputSize) {
    this.parameters = parameters;

    int windowSize = Math.max(1, Math.min(parameters.getWindowSize(), inputSize));
    int blockSize = Math.min(MAX_BLOCK_SIZE, windowSize);
    int divider = (parameters.getSearchLength() == 3) ? 3 : 4;

    int maxSequences = blockSize / divider;

    sequenceStore = new SequenceStore(blockSize, maxSequences);
    blockCompressionState = new BlockCompressionState(parameters, baseAddress);
  }

  void slideWindow(int slideWindowSize) {
    checkArgument(slideWindowSize > 0, "slideWindowSize must be positive");
    blockCompressionState.slideWindow(slideWindowSize);
  }

  void commit() {
    offsets.commit();
    huffmanContext.saveChanges();
  }
}

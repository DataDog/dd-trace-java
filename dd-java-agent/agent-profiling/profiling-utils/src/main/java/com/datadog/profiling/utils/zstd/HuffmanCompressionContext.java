package com.datadog.profiling.utils.zstd;

/** Table management with previous/temporary table swapping for repeat-table optimization. */
final class HuffmanCompressionContext {
  private static final int MAX_SYMBOL_COUNT = 256;

  private final HuffmanTableWriterWorkspace tableWriterWorkspace = new HuffmanTableWriterWorkspace();
  private final HuffmanCompressionTableWorkspace compressionTableWorkspace =
      new HuffmanCompressionTableWorkspace();

  private HuffmanCompressionTable previousTable = new HuffmanCompressionTable(MAX_SYMBOL_COUNT);
  private HuffmanCompressionTable temporaryTable = new HuffmanCompressionTable(MAX_SYMBOL_COUNT);

  private HuffmanCompressionTable previousCandidate = previousTable;
  private HuffmanCompressionTable temporaryCandidate = temporaryTable;

  HuffmanCompressionTable getPreviousTable() {
    return previousTable;
  }

  HuffmanCompressionTable borrowTemporaryTable() {
    previousCandidate = temporaryTable;
    temporaryCandidate = previousTable;
    return temporaryTable;
  }

  void discardTemporaryTable() {
    previousCandidate = previousTable;
    temporaryCandidate = temporaryTable;
  }

  void saveChanges() {
    temporaryTable = temporaryCandidate;
    previousTable = previousCandidate;
  }

  HuffmanCompressionTableWorkspace getCompressionTableWorkspace() {
    return compressionTableWorkspace;
  }

  HuffmanTableWriterWorkspace getTableWriterWorkspace() {
    return tableWriterWorkspace;
  }
}

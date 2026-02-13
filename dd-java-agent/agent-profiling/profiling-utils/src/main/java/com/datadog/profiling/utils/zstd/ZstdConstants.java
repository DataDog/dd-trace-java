package com.datadog.profiling.utils.zstd;

/** Zstd spec constants from RFC 8878. */
final class ZstdConstants {
  static final int SIZE_OF_BYTE = 1;
  static final int SIZE_OF_SHORT = 2;
  static final int SIZE_OF_INT = 4;
  static final int SIZE_OF_LONG = 8;

  static final int MAGIC_NUMBER = 0xFD2FB528;

  static final int MIN_WINDOW_LOG = 10;
  static final int MAX_WINDOW_LOG = 31;

  static final int SIZE_OF_BLOCK_HEADER = 3;

  static final int MIN_SEQUENCES_SIZE = 1;
  static final int MIN_BLOCK_SIZE =
      1 // block type tag
          + 1 // min size of raw or rle length header
          + MIN_SEQUENCES_SIZE;
  static final int MAX_BLOCK_SIZE = 128 * 1024;

  static final int REPEATED_OFFSET_COUNT = 3;

  // block types
  static final int RAW_BLOCK = 0;
  static final int RLE_BLOCK = 1;
  static final int COMPRESSED_BLOCK = 2;

  // sequence encoding types
  static final int SEQUENCE_ENCODING_BASIC = 0;
  static final int SEQUENCE_ENCODING_RLE = 1;
  static final int SEQUENCE_ENCODING_COMPRESSED = 2;
  static final int SEQUENCE_ENCODING_REPEAT = 3;

  static final int MAX_LITERALS_LENGTH_SYMBOL = 35;
  static final int MAX_MATCH_LENGTH_SYMBOL = 52;
  static final int MAX_OFFSET_CODE_SYMBOL = 31;
  static final int DEFAULT_MAX_OFFSET_CODE_SYMBOL = 28;

  static final int LITERAL_LENGTH_TABLE_LOG = 9;
  static final int MATCH_LENGTH_TABLE_LOG = 9;
  static final int OFFSET_TABLE_LOG = 8;

  // literal block types
  static final int RAW_LITERALS_BLOCK = 0;
  static final int RLE_LITERALS_BLOCK = 1;
  static final int COMPRESSED_LITERALS_BLOCK = 2;
  static final int TREELESS_LITERALS_BLOCK = 3;

  static final int LONG_NUMBER_OF_SEQUENCES = 0x7F00;

  static final int[] LITERALS_LENGTH_BITS = {
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 3, 3, 4, 6, 7, 8, 9, 10, 11,
    12, 13, 14, 15, 16
  };

  static final int[] MATCH_LENGTH_BITS = {
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    1, 1, 1, 1, 2, 2, 3, 3, 4, 4, 5, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16
  };

  private ZstdConstants() {}
}

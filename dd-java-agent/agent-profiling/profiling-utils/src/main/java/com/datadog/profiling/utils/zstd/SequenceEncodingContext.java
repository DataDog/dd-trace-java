package com.datadog.profiling.utils.zstd;

import static com.datadog.profiling.utils.zstd.ZstdConstants.LITERAL_LENGTH_TABLE_LOG;
import static com.datadog.profiling.utils.zstd.ZstdConstants.MATCH_LENGTH_TABLE_LOG;
import static com.datadog.profiling.utils.zstd.ZstdConstants.MAX_LITERALS_LENGTH_SYMBOL;
import static com.datadog.profiling.utils.zstd.ZstdConstants.MAX_MATCH_LENGTH_SYMBOL;
import static com.datadog.profiling.utils.zstd.ZstdConstants.MAX_OFFSET_CODE_SYMBOL;
import static com.datadog.profiling.utils.zstd.ZstdConstants.OFFSET_TABLE_LOG;

/** Pre-allocated workspace for FSE encoding of sequences. */
final class SequenceEncodingContext {
  private static final int MAX_SEQUENCES =
      Math.max(MAX_LITERALS_LENGTH_SYMBOL, MAX_MATCH_LENGTH_SYMBOL);

  final FseCompressionTable literalLengthTable =
      new FseCompressionTable(LITERAL_LENGTH_TABLE_LOG, MAX_LITERALS_LENGTH_SYMBOL);
  final FseCompressionTable offsetCodeTable =
      new FseCompressionTable(OFFSET_TABLE_LOG, MAX_OFFSET_CODE_SYMBOL);
  final FseCompressionTable matchLengthTable =
      new FseCompressionTable(MATCH_LENGTH_TABLE_LOG, MAX_MATCH_LENGTH_SYMBOL);

  final int[] counts = new int[MAX_SEQUENCES + 1];
  final short[] normalizedCounts = new short[MAX_SEQUENCES + 1];
}

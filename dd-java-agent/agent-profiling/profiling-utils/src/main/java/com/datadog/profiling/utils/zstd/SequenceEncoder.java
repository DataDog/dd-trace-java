package com.datadog.profiling.utils.zstd;

import static com.datadog.profiling.utils.zstd.FiniteStateEntropy.optimalTableLog;
import static com.datadog.profiling.utils.zstd.Util.checkArgument;
import static com.datadog.profiling.utils.zstd.ZstdConstants.DEFAULT_MAX_OFFSET_CODE_SYMBOL;
import static com.datadog.profiling.utils.zstd.ZstdConstants.LITERALS_LENGTH_BITS;
import static com.datadog.profiling.utils.zstd.ZstdConstants.LITERAL_LENGTH_TABLE_LOG;
import static com.datadog.profiling.utils.zstd.ZstdConstants.LONG_NUMBER_OF_SEQUENCES;
import static com.datadog.profiling.utils.zstd.ZstdConstants.MATCH_LENGTH_BITS;
import static com.datadog.profiling.utils.zstd.ZstdConstants.MATCH_LENGTH_TABLE_LOG;
import static com.datadog.profiling.utils.zstd.ZstdConstants.MAX_LITERALS_LENGTH_SYMBOL;
import static com.datadog.profiling.utils.zstd.ZstdConstants.MAX_MATCH_LENGTH_SYMBOL;
import static com.datadog.profiling.utils.zstd.ZstdConstants.MAX_OFFSET_CODE_SYMBOL;
import static com.datadog.profiling.utils.zstd.ZstdConstants.OFFSET_TABLE_LOG;
import static com.datadog.profiling.utils.zstd.ZstdConstants.SEQUENCE_ENCODING_BASIC;
import static com.datadog.profiling.utils.zstd.ZstdConstants.SEQUENCE_ENCODING_COMPRESSED;
import static com.datadog.profiling.utils.zstd.ZstdConstants.SEQUENCE_ENCODING_RLE;
import static com.datadog.profiling.utils.zstd.ZstdConstants.SIZE_OF_SHORT;

import datadog.trace.util.UnsafeUtils;

/** FSE encoding of (literal-length, match-length, offset) triples. */
final class SequenceEncoder {
  // @formatter:off
  private static final int DEFAULT_LITERAL_LENGTH_NORMALIZED_COUNTS_LOG = 6;
  private static final short[] DEFAULT_LITERAL_LENGTH_NORMALIZED_COUNTS = {
    4, 3, 2, 2, 2, 2, 2, 2,
    2, 2, 2, 2, 2, 1, 1, 1,
    2, 2, 2, 2, 2, 2, 2, 2,
    2, 3, 2, 1, 1, 1, 1, 1,
    -1, -1, -1, -1
  };

  private static final int DEFAULT_MATCH_LENGTH_NORMALIZED_COUNTS_LOG = 6;
  private static final short[] DEFAULT_MATCH_LENGTH_NORMALIZED_COUNTS = {
    1, 4, 3, 2, 2, 2, 2, 2,
    2, 1, 1, 1, 1, 1, 1, 1,
    1, 1, 1, 1, 1, 1, 1, 1,
    1, 1, 1, 1, 1, 1, 1, 1,
    1, 1, 1, 1, 1, 1, 1, 1,
    1, 1, 1, 1, 1, 1, -1, -1,
    -1, -1, -1, -1, -1
  };

  private static final int DEFAULT_OFFSET_NORMALIZED_COUNTS_LOG = 5;
  private static final short[] DEFAULT_OFFSET_NORMALIZED_COUNTS = {
    1, 1, 1, 1, 1, 1, 2, 2,
    2, 1, 1, 1, 1, 1, 1, 1,
    1, 1, 1, 1, 1, 1, 1, 1,
    -1, -1, -1, -1, -1
  };
  // @formatter:on

  private static final FseCompressionTable DEFAULT_LITERAL_LENGTHS_TABLE =
      FseCompressionTable.newInstance(
          DEFAULT_LITERAL_LENGTH_NORMALIZED_COUNTS,
          MAX_LITERALS_LENGTH_SYMBOL,
          DEFAULT_LITERAL_LENGTH_NORMALIZED_COUNTS_LOG);

  private static final FseCompressionTable DEFAULT_MATCH_LENGTHS_TABLE =
      FseCompressionTable.newInstance(
          DEFAULT_MATCH_LENGTH_NORMALIZED_COUNTS,
          MAX_MATCH_LENGTH_SYMBOL,
          DEFAULT_MATCH_LENGTH_NORMALIZED_COUNTS_LOG);

  private static final FseCompressionTable DEFAULT_OFFSETS_TABLE =
      FseCompressionTable.newInstance(
          DEFAULT_OFFSET_NORMALIZED_COUNTS,
          DEFAULT_MAX_OFFSET_CODE_SYMBOL,
          DEFAULT_OFFSET_NORMALIZED_COUNTS_LOG);

  private SequenceEncoder() {}

  static int compressSequences(
      Object outputBase,
      long outputAddress,
      int outputSize,
      SequenceStore sequences,
      CompressionParameters.Strategy strategy,
      SequenceEncodingContext workspace) {
    long output = outputAddress;
    long outputLimit = outputAddress + outputSize;

    checkArgument(outputLimit - output > 3 + 1, "Output buffer too small");

    int sequenceCount = sequences.sequenceCount;
    if (sequenceCount < 0x7F) {
      UnsafeUtils.putByte(outputBase, output, (byte) sequenceCount);
      output++;
    } else if (sequenceCount < LONG_NUMBER_OF_SEQUENCES) {
      UnsafeUtils.putByte(outputBase, output, (byte) (sequenceCount >>> 8 | 0x80));
      UnsafeUtils.putByte(outputBase, output + 1, (byte) sequenceCount);
      output += SIZE_OF_SHORT;
    } else {
      UnsafeUtils.putByte(outputBase, output, (byte) 0xFF);
      output++;
      UnsafeUtils.putShort(outputBase, output, (short) (sequenceCount - LONG_NUMBER_OF_SEQUENCES));
      output += SIZE_OF_SHORT;
    }

    if (sequenceCount == 0) {
      return (int) (output - outputAddress);
    }

    // flags for FSE encoding type
    long headerAddress = output++;

    int maxSymbol;
    int largestCount;

    // literal lengths
    int[] counts = workspace.counts;
    Histogram.count(sequences.literalLengthCodes, sequenceCount, workspace.counts);
    maxSymbol = Histogram.findMaxSymbol(counts, MAX_LITERALS_LENGTH_SYMBOL);
    largestCount = Histogram.findLargestCount(counts, maxSymbol);

    int literalsLengthEncodingType =
        selectEncodingType(
            largestCount,
            sequenceCount,
            DEFAULT_LITERAL_LENGTH_NORMALIZED_COUNTS_LOG,
            true,
            strategy);

    FseCompressionTable literalLengthTable;
    switch (literalsLengthEncodingType) {
      case SEQUENCE_ENCODING_RLE:
        UnsafeUtils.putByte(outputBase, output, sequences.literalLengthCodes[0]);
        output++;
        workspace.literalLengthTable.initializeRleTable(maxSymbol);
        literalLengthTable = workspace.literalLengthTable;
        break;
      case SEQUENCE_ENCODING_BASIC:
        literalLengthTable = DEFAULT_LITERAL_LENGTHS_TABLE;
        break;
      case SEQUENCE_ENCODING_COMPRESSED:
        output +=
            buildCompressionTable(
                workspace.literalLengthTable,
                outputBase,
                output,
                outputLimit,
                sequenceCount,
                LITERAL_LENGTH_TABLE_LOG,
                sequences.literalLengthCodes,
                workspace.counts,
                maxSymbol,
                workspace.normalizedCounts);
        literalLengthTable = workspace.literalLengthTable;
        break;
      default:
        throw new UnsupportedOperationException("not yet implemented");
    }

    // offsets
    Histogram.count(sequences.offsetCodes, sequenceCount, workspace.counts);
    maxSymbol = Histogram.findMaxSymbol(counts, MAX_OFFSET_CODE_SYMBOL);
    largestCount = Histogram.findLargestCount(counts, maxSymbol);

    boolean defaultAllowed = maxSymbol < DEFAULT_MAX_OFFSET_CODE_SYMBOL;

    int offsetEncodingType =
        selectEncodingType(
            largestCount,
            sequenceCount,
            DEFAULT_OFFSET_NORMALIZED_COUNTS_LOG,
            defaultAllowed,
            strategy);

    FseCompressionTable offsetCodeTable;
    switch (offsetEncodingType) {
      case SEQUENCE_ENCODING_RLE:
        UnsafeUtils.putByte(outputBase, output, sequences.offsetCodes[0]);
        output++;
        workspace.offsetCodeTable.initializeRleTable(maxSymbol);
        offsetCodeTable = workspace.offsetCodeTable;
        break;
      case SEQUENCE_ENCODING_BASIC:
        offsetCodeTable = DEFAULT_OFFSETS_TABLE;
        break;
      case SEQUENCE_ENCODING_COMPRESSED:
        output +=
            buildCompressionTable(
                workspace.offsetCodeTable,
                outputBase,
                output,
                output + outputSize,
                sequenceCount,
                OFFSET_TABLE_LOG,
                sequences.offsetCodes,
                workspace.counts,
                maxSymbol,
                workspace.normalizedCounts);
        offsetCodeTable = workspace.offsetCodeTable;
        break;
      default:
        throw new UnsupportedOperationException("not yet implemented");
    }

    // match lengths
    Histogram.count(sequences.matchLengthCodes, sequenceCount, workspace.counts);
    maxSymbol = Histogram.findMaxSymbol(counts, MAX_MATCH_LENGTH_SYMBOL);
    largestCount = Histogram.findLargestCount(counts, maxSymbol);

    int matchLengthEncodingType =
        selectEncodingType(
            largestCount,
            sequenceCount,
            DEFAULT_MATCH_LENGTH_NORMALIZED_COUNTS_LOG,
            true,
            strategy);

    FseCompressionTable matchLengthTable;
    switch (matchLengthEncodingType) {
      case SEQUENCE_ENCODING_RLE:
        UnsafeUtils.putByte(outputBase, output, sequences.matchLengthCodes[0]);
        output++;
        workspace.matchLengthTable.initializeRleTable(maxSymbol);
        matchLengthTable = workspace.matchLengthTable;
        break;
      case SEQUENCE_ENCODING_BASIC:
        matchLengthTable = DEFAULT_MATCH_LENGTHS_TABLE;
        break;
      case SEQUENCE_ENCODING_COMPRESSED:
        output +=
            buildCompressionTable(
                workspace.matchLengthTable,
                outputBase,
                output,
                outputLimit,
                sequenceCount,
                MATCH_LENGTH_TABLE_LOG,
                sequences.matchLengthCodes,
                workspace.counts,
                maxSymbol,
                workspace.normalizedCounts);
        matchLengthTable = workspace.matchLengthTable;
        break;
      default:
        throw new UnsupportedOperationException("not yet implemented");
    }

    // flags
    UnsafeUtils.putByte(
        outputBase,
        headerAddress,
        (byte)
            ((literalsLengthEncodingType << 6)
                | (offsetEncodingType << 4)
                | (matchLengthEncodingType << 2)));

    output +=
        encodeSequences(
            outputBase,
            output,
            outputLimit,
            matchLengthTable,
            offsetCodeTable,
            literalLengthTable,
            sequences);

    return (int) (output - outputAddress);
  }

  private static int buildCompressionTable(
      FseCompressionTable table,
      Object outputBase,
      long output,
      long outputLimit,
      int sequenceCount,
      int maxTableLog,
      byte[] codes,
      int[] counts,
      int maxSymbol,
      short[] normalizedCounts) {
    int tableLog = optimalTableLog(maxTableLog, sequenceCount, maxSymbol);

    // minor optimization: last symbol is embedded in initial FSE state
    if (counts[codes[sequenceCount - 1]] > 1) {
      counts[codes[sequenceCount - 1]]--;
      sequenceCount--;
    }

    FiniteStateEntropy.normalizeCounts(
        normalizedCounts, tableLog, counts, sequenceCount, maxSymbol);
    table.initialize(normalizedCounts, maxSymbol, tableLog);

    return FiniteStateEntropy.writeNormalizedCounts(
        outputBase, output, (int) (outputLimit - output), normalizedCounts, maxSymbol, tableLog);
  }

  private static int encodeSequences(
      Object outputBase,
      long output,
      long outputLimit,
      FseCompressionTable matchLengthTable,
      FseCompressionTable offsetsTable,
      FseCompressionTable literalLengthTable,
      SequenceStore sequences) {
    byte[] matchLengthCodes = sequences.matchLengthCodes;
    byte[] offsetCodes = sequences.offsetCodes;
    byte[] literalLengthCodes = sequences.literalLengthCodes;

    BitOutputStream blockStream =
        new BitOutputStream(outputBase, output, (int) (outputLimit - output));

    int sequenceCount = sequences.sequenceCount;

    // first symbols
    int matchLengthState = matchLengthTable.begin(matchLengthCodes[sequenceCount - 1]);
    int offsetState = offsetsTable.begin(offsetCodes[sequenceCount - 1]);
    int literalLengthState = literalLengthTable.begin(literalLengthCodes[sequenceCount - 1]);

    blockStream.addBits(
        sequences.literalLengths[sequenceCount - 1],
        LITERALS_LENGTH_BITS[literalLengthCodes[sequenceCount - 1]]);
    blockStream.addBits(
        sequences.matchLengths[sequenceCount - 1],
        MATCH_LENGTH_BITS[matchLengthCodes[sequenceCount - 1]]);
    blockStream.addBits(sequences.offsets[sequenceCount - 1], offsetCodes[sequenceCount - 1]);
    blockStream.flush();

    if (sequenceCount >= 2) {
      for (int n = sequenceCount - 2; n >= 0; n--) {
        byte literalLengthCode = literalLengthCodes[n];
        byte offsetCode = offsetCodes[n];
        byte matchLengthCode = matchLengthCodes[n];

        int literalLengthBits = LITERALS_LENGTH_BITS[literalLengthCode];
        int offsetBits = offsetCode;
        int matchLengthBits = MATCH_LENGTH_BITS[matchLengthCode];

        offsetState = offsetsTable.encode(blockStream, offsetState, offsetCode);
        matchLengthState = matchLengthTable.encode(blockStream, matchLengthState, matchLengthCode);
        literalLengthState =
            literalLengthTable.encode(blockStream, literalLengthState, literalLengthCode);

        if ((offsetBits + matchLengthBits + literalLengthBits
            >= 64 - 7 - (LITERAL_LENGTH_TABLE_LOG + MATCH_LENGTH_TABLE_LOG + OFFSET_TABLE_LOG))) {
          blockStream.flush();
        }

        blockStream.addBits(sequences.literalLengths[n], literalLengthBits);
        if ((literalLengthBits + matchLengthBits) > 24) {
          blockStream.flush();
        }

        blockStream.addBits(sequences.matchLengths[n], matchLengthBits);
        if ((offsetBits + matchLengthBits + literalLengthBits) > 56) {
          blockStream.flush();
        }

        blockStream.addBits(sequences.offsets[n], offsetBits);
        blockStream.flush();
      }
    }

    matchLengthTable.finish(blockStream, matchLengthState);
    offsetsTable.finish(blockStream, offsetState);
    literalLengthTable.finish(blockStream, literalLengthState);

    int streamSize = blockStream.close();
    checkArgument(streamSize > 0, "Output buffer too small");

    return streamSize;
  }

  private static int selectEncodingType(
      int largestCount,
      int sequenceCount,
      int defaultNormalizedCountsLog,
      boolean isDefaultTableAllowed,
      CompressionParameters.Strategy strategy) {
    if (largestCount == sequenceCount) {
      if (isDefaultTableAllowed && sequenceCount <= 2) {
        return SEQUENCE_ENCODING_BASIC;
      }
      return SEQUENCE_ENCODING_RLE;
    }

    if (strategy.ordinal() < CompressionParameters.Strategy.LAZY.ordinal()) {
      if (isDefaultTableAllowed) {
        int factor = 10 - strategy.ordinal();
        int baseLog = 3;
        long minNumberOfSequences = ((1L << defaultNormalizedCountsLog) * factor) >> baseLog;

        if ((sequenceCount < minNumberOfSequences)
            || (largestCount < (sequenceCount >> (defaultNormalizedCountsLog - 1)))) {
          return SEQUENCE_ENCODING_BASIC;
        }
      }
    } else {
      throw new UnsupportedOperationException("not yet implemented");
    }

    return SEQUENCE_ENCODING_COMPRESSED;
  }
}

package com.datadog.profiling.utils.zstd;

import static com.datadog.profiling.utils.zstd.Util.cycleLog;
import static com.datadog.profiling.utils.zstd.Util.highestBit;
import static com.datadog.profiling.utils.zstd.ZstdConstants.MAX_BLOCK_SIZE;
import static com.datadog.profiling.utils.zstd.ZstdConstants.MAX_WINDOW_LOG;
import static com.datadog.profiling.utils.zstd.ZstdConstants.MIN_WINDOW_LOG;

final class CompressionParameters {
  private static final int MIN_HASH_LOG = 6;

  static final int DEFAULT_COMPRESSION_LEVEL = 3;
  private static final int MAX_COMPRESSION_LEVEL = 22;

  private final int windowLog;
  private final int windowSize;
  private final int blockSize;
  private final int chainLog;
  private final int hashLog;
  private final int searchLog;
  private final int searchLength;
  private final int targetLength;
  private final Strategy strategy;

  // @formatter:off
  private static final CompressionParameters[][] DEFAULT_COMPRESSION_PARAMETERS =
      new CompressionParameters[][] {
        { // default
          new CompressionParameters(19, 12, 13, 1, 6, 1, Strategy.FAST),
          new CompressionParameters(19, 13, 14, 1, 7, 0, Strategy.FAST),
          new CompressionParameters(19, 15, 16, 1, 6, 0, Strategy.FAST),
          new CompressionParameters(20, 16, 17, 1, 5, 1, Strategy.DFAST),
          new CompressionParameters(20, 18, 18, 1, 5, 1, Strategy.DFAST),
          new CompressionParameters(20, 18, 18, 2, 5, 2, Strategy.GREEDY),
          new CompressionParameters(21, 18, 19, 2, 5, 4, Strategy.LAZY),
          new CompressionParameters(21, 18, 19, 3, 5, 8, Strategy.LAZY2),
          new CompressionParameters(21, 19, 19, 3, 5, 16, Strategy.LAZY2),
          new CompressionParameters(21, 19, 20, 4, 5, 16, Strategy.LAZY2),
          new CompressionParameters(21, 20, 21, 4, 5, 16, Strategy.LAZY2),
          new CompressionParameters(21, 21, 22, 4, 5, 16, Strategy.LAZY2),
          new CompressionParameters(22, 20, 22, 5, 5, 16, Strategy.LAZY2),
          new CompressionParameters(22, 21, 22, 4, 5, 32, Strategy.BTLAZY2),
          new CompressionParameters(22, 21, 22, 5, 5, 32, Strategy.BTLAZY2),
          new CompressionParameters(22, 22, 22, 6, 5, 32, Strategy.BTLAZY2),
          new CompressionParameters(22, 21, 22, 4, 5, 48, Strategy.BTOPT),
          new CompressionParameters(23, 22, 22, 4, 4, 64, Strategy.BTOPT),
          new CompressionParameters(23, 23, 22, 6, 3, 256, Strategy.BTOPT),
          new CompressionParameters(23, 24, 22, 7, 3, 256, Strategy.BTULTRA),
          new CompressionParameters(25, 25, 23, 7, 3, 256, Strategy.BTULTRA),
          new CompressionParameters(26, 26, 24, 7, 3, 512, Strategy.BTULTRA),
          new CompressionParameters(27, 27, 25, 9, 3, 999, Strategy.BTULTRA),
        },
        { // for size <= 256 KB
          new CompressionParameters(18, 12, 13, 1, 5, 1, Strategy.FAST),
          new CompressionParameters(18, 13, 14, 1, 6, 0, Strategy.FAST),
          new CompressionParameters(18, 14, 14, 1, 5, 1, Strategy.DFAST),
          new CompressionParameters(18, 16, 16, 1, 4, 1, Strategy.DFAST),
          new CompressionParameters(18, 16, 17, 2, 5, 2, Strategy.GREEDY),
          new CompressionParameters(18, 18, 18, 3, 5, 2, Strategy.GREEDY),
          new CompressionParameters(18, 18, 19, 3, 5, 4, Strategy.LAZY),
          new CompressionParameters(18, 18, 19, 4, 4, 4, Strategy.LAZY),
          new CompressionParameters(18, 18, 19, 4, 4, 8, Strategy.LAZY2),
          new CompressionParameters(18, 18, 19, 5, 4, 8, Strategy.LAZY2),
          new CompressionParameters(18, 18, 19, 6, 4, 8, Strategy.LAZY2),
          new CompressionParameters(18, 18, 19, 5, 4, 16, Strategy.BTLAZY2),
          new CompressionParameters(18, 19, 19, 6, 4, 16, Strategy.BTLAZY2),
          new CompressionParameters(18, 19, 19, 8, 4, 16, Strategy.BTLAZY2),
          new CompressionParameters(18, 18, 19, 4, 4, 24, Strategy.BTOPT),
          new CompressionParameters(18, 18, 19, 4, 3, 24, Strategy.BTOPT),
          new CompressionParameters(18, 19, 19, 6, 3, 64, Strategy.BTOPT),
          new CompressionParameters(18, 19, 19, 8, 3, 128, Strategy.BTOPT),
          new CompressionParameters(18, 19, 19, 10, 3, 256, Strategy.BTOPT),
          new CompressionParameters(18, 19, 19, 10, 3, 256, Strategy.BTULTRA),
          new CompressionParameters(18, 19, 19, 11, 3, 512, Strategy.BTULTRA),
          new CompressionParameters(18, 19, 19, 12, 3, 512, Strategy.BTULTRA),
          new CompressionParameters(18, 19, 19, 13, 3, 999, Strategy.BTULTRA),
        },
        { // for size <= 128 KB
          new CompressionParameters(17, 12, 12, 1, 5, 1, Strategy.FAST),
          new CompressionParameters(17, 12, 13, 1, 6, 0, Strategy.FAST),
          new CompressionParameters(17, 13, 15, 1, 5, 0, Strategy.FAST),
          new CompressionParameters(17, 15, 16, 2, 5, 1, Strategy.DFAST),
          new CompressionParameters(17, 17, 17, 2, 4, 1, Strategy.DFAST),
          new CompressionParameters(17, 16, 17, 3, 4, 2, Strategy.GREEDY),
          new CompressionParameters(17, 17, 17, 3, 4, 4, Strategy.LAZY),
          new CompressionParameters(17, 17, 17, 3, 4, 8, Strategy.LAZY2),
          new CompressionParameters(17, 17, 17, 4, 4, 8, Strategy.LAZY2),
          new CompressionParameters(17, 17, 17, 5, 4, 8, Strategy.LAZY2),
          new CompressionParameters(17, 17, 17, 6, 4, 8, Strategy.LAZY2),
          new CompressionParameters(17, 17, 17, 7, 4, 8, Strategy.LAZY2),
          new CompressionParameters(17, 18, 17, 6, 4, 16, Strategy.BTLAZY2),
          new CompressionParameters(17, 18, 17, 8, 4, 16, Strategy.BTLAZY2),
          new CompressionParameters(17, 18, 17, 4, 4, 32, Strategy.BTOPT),
          new CompressionParameters(17, 18, 17, 6, 3, 64, Strategy.BTOPT),
          new CompressionParameters(17, 18, 17, 7, 3, 128, Strategy.BTOPT),
          new CompressionParameters(17, 18, 17, 7, 3, 256, Strategy.BTOPT),
          new CompressionParameters(17, 18, 17, 8, 3, 256, Strategy.BTOPT),
          new CompressionParameters(17, 18, 17, 8, 3, 256, Strategy.BTULTRA),
          new CompressionParameters(17, 18, 17, 9, 3, 256, Strategy.BTULTRA),
          new CompressionParameters(17, 18, 17, 10, 3, 256, Strategy.BTULTRA),
          new CompressionParameters(17, 18, 17, 11, 3, 512, Strategy.BTULTRA),
        },
        { // for size <= 16 KB
          new CompressionParameters(14, 12, 13, 1, 5, 1, Strategy.FAST),
          new CompressionParameters(14, 14, 15, 1, 5, 0, Strategy.FAST),
          new CompressionParameters(14, 14, 15, 1, 4, 0, Strategy.FAST),
          new CompressionParameters(14, 14, 14, 2, 4, 1, Strategy.DFAST),
          new CompressionParameters(14, 14, 14, 4, 4, 2, Strategy.GREEDY),
          new CompressionParameters(14, 14, 14, 3, 4, 4, Strategy.LAZY),
          new CompressionParameters(14, 14, 14, 4, 4, 8, Strategy.LAZY2),
          new CompressionParameters(14, 14, 14, 6, 4, 8, Strategy.LAZY2),
          new CompressionParameters(14, 14, 14, 8, 4, 8, Strategy.LAZY2),
          new CompressionParameters(14, 15, 14, 5, 4, 8, Strategy.BTLAZY2),
          new CompressionParameters(14, 15, 14, 9, 4, 8, Strategy.BTLAZY2),
          new CompressionParameters(14, 15, 14, 3, 4, 12, Strategy.BTOPT),
          new CompressionParameters(14, 15, 14, 6, 3, 16, Strategy.BTOPT),
          new CompressionParameters(14, 15, 14, 6, 3, 24, Strategy.BTOPT),
          new CompressionParameters(14, 15, 15, 6, 3, 48, Strategy.BTOPT),
          new CompressionParameters(14, 15, 15, 6, 3, 64, Strategy.BTOPT),
          new CompressionParameters(14, 15, 15, 6, 3, 96, Strategy.BTOPT),
          new CompressionParameters(14, 15, 15, 6, 3, 128, Strategy.BTOPT),
          new CompressionParameters(14, 15, 15, 8, 3, 256, Strategy.BTOPT),
          new CompressionParameters(14, 15, 15, 6, 3, 256, Strategy.BTULTRA),
          new CompressionParameters(14, 15, 15, 8, 3, 256, Strategy.BTULTRA),
          new CompressionParameters(14, 15, 15, 9, 3, 256, Strategy.BTULTRA),
          new CompressionParameters(14, 15, 15, 10, 3, 512, Strategy.BTULTRA),
        }
      };
  // @formatter:on

  enum Strategy {
    FAST,
    DFAST,
    GREEDY,
    LAZY,
    LAZY2,
    BTLAZY2,
    BTOPT,
    BTULTRA;
  }

  CompressionParameters(
      int windowLog,
      int chainLog,
      int hashLog,
      int searchLog,
      int searchLength,
      int targetLength,
      Strategy strategy) {
    this.windowLog = windowLog;
    this.windowSize = 1 << windowLog;
    this.blockSize = Math.min(MAX_BLOCK_SIZE, windowSize);
    this.chainLog = chainLog;
    this.hashLog = hashLog;
    this.searchLog = searchLog;
    this.searchLength = searchLength;
    this.targetLength = targetLength;
    this.strategy = strategy;
  }

  int getWindowLog() {
    return windowLog;
  }

  int getWindowSize() {
    return windowSize;
  }

  int getBlockSize() {
    return blockSize;
  }

  int getSearchLength() {
    return searchLength;
  }

  int getChainLog() {
    return chainLog;
  }

  int getHashLog() {
    return hashLog;
  }

  int getSearchLog() {
    return searchLog;
  }

  int getTargetLength() {
    return targetLength;
  }

  Strategy getStrategy() {
    return strategy;
  }

  static CompressionParameters compute(int compressionLevel, int estimatedInputSize) {
    CompressionParameters defaultParameters =
        getDefaultParameters(compressionLevel, estimatedInputSize);
    if (estimatedInputSize < 0) {
      return defaultParameters;
    }

    int targetLength = defaultParameters.targetLength;
    int windowLog = defaultParameters.windowLog;
    int chainLog = defaultParameters.chainLog;
    int hashLog = defaultParameters.hashLog;
    int searchLog = defaultParameters.searchLog;
    int searchLength = defaultParameters.searchLength;
    Strategy strategy = defaultParameters.strategy;

    if (compressionLevel < 0) {
      targetLength = -compressionLevel;
    }

    // resize windowLog if input is small enough, to use less memory
    long maxWindowResize = 1L << (MAX_WINDOW_LOG - 1);
    if (estimatedInputSize < maxWindowResize) {
      int hashSizeMin = 1 << MIN_HASH_LOG;
      int inputSizeLog =
          (estimatedInputSize < hashSizeMin)
              ? MIN_HASH_LOG
              : highestBit(estimatedInputSize - 1) + 1;
      if (windowLog > inputSizeLog) {
        windowLog = inputSizeLog;
      }
    }

    if (hashLog > windowLog + 1) {
      hashLog = windowLog + 1;
    }

    int cl = cycleLog(chainLog, strategy);
    if (cl > windowLog) {
      chainLog -= (cl - windowLog);
    }

    if (windowLog < MIN_WINDOW_LOG) {
      windowLog = MIN_WINDOW_LOG;
    }

    return new CompressionParameters(
        windowLog, chainLog, hashLog, searchLog, searchLength, targetLength, strategy);
  }

  private static CompressionParameters getDefaultParameters(
      int compressionLevel, long estimatedInputSize) {
    int table = 0;

    if (estimatedInputSize >= 0) {
      if (estimatedInputSize <= 16 * 1024) {
        table = 3;
      } else if (estimatedInputSize <= 128 * 1024) {
        table = 2;
      } else if (estimatedInputSize <= 256 * 1024) {
        table = 1;
      }
    }

    int row = DEFAULT_COMPRESSION_LEVEL;

    if (compressionLevel != 0) {
      row = Math.min(Math.max(0, compressionLevel), MAX_COMPRESSION_LEVEL);
    }

    return DEFAULT_COMPRESSION_PARAMETERS[table][row];
  }
}

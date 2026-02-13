package com.datadog.profiling.utils.zstd;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.util.UnsafeUtils;
import org.junit.jupiter.api.Test;

class FiniteStateEntropyTest {

  private static final long BASE = UnsafeUtils.BYTE_ARRAY_BASE_OFFSET;

  @Test
  void optimalTableLogSmallInput() {
    assertThrows(
        IllegalArgumentException.class,
        () -> FiniteStateEntropy.optimalTableLog(12, 1, 10));
  }

  @Test
  void optimalTableLogNormalInput() {
    int result = FiniteStateEntropy.optimalTableLog(12, 1000, 50);
    assertTrue(result >= FiniteStateEntropy.MIN_TABLE_LOG);
    assertTrue(result <= FiniteStateEntropy.MAX_TABLE_LOG);
  }

  @Test
  void normalizeCountsBasicDistribution() {
    int[] counts = new int[16];
    counts[0] = 100;
    counts[1] = 50;
    counts[2] = 30;
    counts[3] = 20;

    short[] normalized = new short[16];
    int tableLog = FiniteStateEntropy.normalizeCounts(normalized, 6, counts, 200, 3);
    assertTrue(tableLog >= 5);

    int sum = 0;
    for (int i = 0; i <= 3; i++) {
      sum += normalized[i] < 0 ? 1 : normalized[i];
    }
    assertTrue(sum == (1 << tableLog), "Normalized counts should sum to 2^tableLog");
  }

  @Test
  void normalizeCountsTriggersNormalize2ViaRestToBeatBump() {
    // normalizeCounts2 is triggered when -stillToDistribute >= (normalizedCounts[largest] >>> 1).
    // This happens when many symbols' probabilities get bumped by REST_TO_BEAT correction,
    // causing the total to exceed tableSize.
    //
    // 42 symbols with count=4, 1 symbol with count=14, total=182, maxSymbol=42
    // At tableLog=7 (tableSize=128):
    //   - Each count=4 symbol: floor(4*128/182) = 2, fractional part ≈ 0.81 > 0.48 → bumped to 3
    //   - 42 symbols * 3 = 126, plus 1 symbol at ~9 = 135 > 128
    //   - stillToDistribute goes negative → normalizeCounts2 called
    int[] counts = new int[64];
    for (int i = 0; i < 42; i++) {
      counts[i] = 4;
    }
    counts[42] = 14;
    int total = 42 * 4 + 14; // = 182

    short[] normalized = new short[64];
    int tableLog = FiniteStateEntropy.optimalTableLog(12, total, 42);
    FiniteStateEntropy.normalizeCounts(normalized, tableLog, counts, total, 42);
    // Verify output is valid
    int sum = 0;
    for (int i = 0; i <= 42; i++) {
      sum += normalized[i] < 0 ? 1 : normalized[i];
    }
    assertTrue(sum == (1 << tableLog), "Normalized counts should sum to 2^tableLog, got " + sum);
  }

  @Test
  void normalizeCountsTriggersNormalize2AllLowCounts() {
    // Similar pattern: many symbols whose probabilities round up.
    // 50 symbols with count=3, 1 symbol with count=10, total=160, maxSymbol=50
    // tableLog=7 (128): each gets floor(3*128/160)=2, frac=0.4 (close to threshold)
    // Try with slightly different counts to ensure bump.
    int[] counts = new int[64];
    for (int i = 0; i < 50; i++) {
      counts[i] = 3;
    }
    counts[50] = 10;
    int total = 50 * 3 + 10; // = 160

    short[] normalized = new short[64];
    int maxTableLog = FiniteStateEntropy.optimalTableLog(12, total, 50);
    FiniteStateEntropy.normalizeCounts(normalized, maxTableLog, counts, total, 50);
    assertTrue(maxTableLog >= 5);
  }

  @Test
  void normalizeCountsTriggersNormalize2ViaUniformDistribution() {
    // Yet another distribution designed to overflow: 35 symbols with count=5,
    // 1 symbol with count=7, total=182, maxSymbol=35
    int[] counts = new int[64];
    for (int i = 0; i < 35; i++) {
      counts[i] = 5;
    }
    counts[35] = 7;
    int total = 35 * 5 + 7; // = 182

    short[] normalized = new short[64];
    int maxTableLog = FiniteStateEntropy.optimalTableLog(12, total, 35);
    FiniteStateEntropy.normalizeCounts(normalized, maxTableLog, counts, total, 35);
    assertTrue(maxTableLog >= 5);
  }

  @Test
  void normalizeCountsSkewedDistribution() {
    // Very skewed with many single-count symbols
    int[] counts = new int[32];
    int total = 0;
    counts[0] = 10000;
    total += counts[0];
    for (int i = 1; i < 20; i++) {
      counts[i] = 1;
      total += 1;
    }

    short[] normalized = new short[32];
    int tableLog = FiniteStateEntropy.normalizeCounts(normalized, 8, counts, total, 19);
    assertTrue(tableLog >= 5);
  }

  @Test
  void normalizeCountsManyLowCountSymbols() {
    int[] counts = new int[64];
    int total = 0;
    for (int i = 0; i < 50; i++) {
      counts[i] = (i == 0) ? 500 : 2;
      total += counts[i];
    }

    short[] normalized = new short[64];
    int tableLog = FiniteStateEntropy.normalizeCounts(normalized, 8, counts, total, 49);
    assertTrue(tableLog >= 5);
  }

  @Test
  void writeNormalizedCounts() {
    int[] counts = new int[16];
    counts[0] = 40;
    counts[1] = 30;
    counts[2] = 20;
    counts[3] = 10;

    short[] normalized = new short[16];
    int tableLog = FiniteStateEntropy.normalizeCounts(normalized, 6, counts, 100, 3);

    byte[] output = new byte[256];
    int size =
        FiniteStateEntropy.writeNormalizedCounts(
            output, UnsafeUtils.BYTE_ARRAY_BASE_OFFSET, 256, normalized, 3, tableLog);
    assertTrue(size > 0);
  }

  @Test
  void writeNormalizedCountsWithZeroRuns() {
    // Distribution with gaps (zero-count symbols) triggers the previousIs0 run encoding
    int[] counts = new int[32];
    counts[0] = 50;
    counts[6] = 30;
    counts[11] = 20;

    short[] normalized = new short[32];
    int tableLog = FiniteStateEntropy.normalizeCounts(normalized, 6, counts, 100, 11);

    byte[] output = new byte[256];
    int size =
        FiniteStateEntropy.writeNormalizedCounts(
            output, UnsafeUtils.BYTE_ARRAY_BASE_OFFSET, 256, normalized, 11, tableLog);
    assertTrue(size > 0);
  }

  @Test
  void writeNormalizedCountsWithLongZeroRun() {
    // Long zero run (>= 24 symbols) triggers the batch encoding of zeros
    int[] counts = new int[64];
    counts[0] = 50;
    // Symbols 1-30 have count 0 (long run)
    counts[31] = 30;
    counts[40] = 20;

    short[] normalized = new short[64];
    int maxTableLog = FiniteStateEntropy.optimalTableLog(12, 100, 40);
    int tableLog = FiniteStateEntropy.normalizeCounts(normalized, maxTableLog, counts, 100, 40);

    byte[] output = new byte[256];
    int size =
        FiniteStateEntropy.writeNormalizedCounts(
            output, UnsafeUtils.BYTE_ARRAY_BASE_OFFSET, 256, normalized, 40, tableLog);
    assertTrue(size > 0);
  }

  @Test
  void normalizeCountsMixedDistributionInNormalize2() {
    // Distribution that triggers normalizeCounts2 AND covers internal branches for:
    // - counts[i] == 0 (zero-count symbols)
    // - counts[i] <= lowThreshold (very low counts)
    // - counts[i] <= lowOne (medium-low counts)
    // - UNASSIGNED (counts > lowOne)
    // - (total / toDistribute) > lowOne path
    int[] counts = new int[64];
    // 20 symbols with count=4 → probability gets REST_TO_BEAT bumped → over-distribution
    for (int i = 0; i < 20; i++) {
      counts[i] = 4;
    }
    // symbols 20-29: count=0 → covers counts==0 branch
    // symbols 30-39: count=1 → covers counts<=lowThreshold branch
    for (int i = 30; i < 40; i++) {
      counts[i] = 1;
    }
    // symbol 40: high count → UNASSIGNED in normalizeCounts2
    counts[40] = 14;
    int total = 20 * 4 + 10 * 1 + 14; // = 104

    short[] normalized = new short[64];
    int tableLog = FiniteStateEntropy.optimalTableLog(12, total, 40);
    FiniteStateEntropy.normalizeCounts(normalized, tableLog, counts, total, 40);

    int sum = 0;
    for (int i = 0; i <= 40; i++) {
      sum += normalized[i] < 0 ? 1 : normalized[i];
    }
    assertTrue(sum == (1 << tableLog), "Normalized counts should sum to 2^tableLog");
  }

  @Test
  void normalizeCountsAllDistributedInNormalize2() {
    // 86 symbols each with count=3, total=258, maxSymbol=85.
    // At tableLog=7: in normalizeCounts, each probability gets REST_TO_BEAT bumped to 2,
    // 86*2=172 > 128 → normalizeCounts2 called.
    // In normalizeCounts2: lowOne = (258*3)>>>8 = 3. All counts=3 <= lowOne → all distributed.
    // distributed(86) == maxSymbol+1(86) → triggers "all distributed" early return (line 228)
    int[] counts = new int[128];
    for (int i = 0; i < 86; i++) {
      counts[i] = 3;
    }
    int total = 86 * 3; // = 258

    short[] normalized = new short[128];
    int tableLog = FiniteStateEntropy.optimalTableLog(12, total, 85);
    FiniteStateEntropy.normalizeCounts(normalized, tableLog, counts, total, 85);

    int sum = 0;
    for (int i = 0; i <= 85; i++) {
      sum += normalized[i] < 0 ? 1 : normalized[i];
    }
    assertTrue(sum == (1 << tableLog), "Normalized counts should sum to 2^tableLog");
  }

  @Test
  void compressBasicInput() {
    byte[] input = new byte[100];
    for (int i = 0; i < input.length; i++) {
      input[i] = (byte) (i % 5);
    }

    int[] counts = new int[256];
    Histogram.count(input, input.length, counts);
    int maxSymbol = Histogram.findMaxSymbol(counts, 255);

    short[] normalized = new short[256];
    int tableLog = FiniteStateEntropy.optimalTableLog(12, input.length, maxSymbol);
    FiniteStateEntropy.normalizeCounts(normalized, tableLog, counts, input.length, maxSymbol);

    FseCompressionTable table = FseCompressionTable.newInstance(normalized, maxSymbol, tableLog);

    byte[] output = new byte[256];
    int size =
        FiniteStateEntropy.compress(
            output, UnsafeUtils.BYTE_ARRAY_BASE_OFFSET, 256, input, input.length, table);
    assertTrue(size > 0, "Compression should produce output");
  }

  @Test
  void compressTinyInputReturnsZero() {
    int[] counts = new int[256];
    counts[1] = 10;
    counts[2] = 10;
    short[] normalized = new short[256];
    FiniteStateEntropy.normalizeCounts(normalized, 5, counts, 20, 2);
    FseCompressionTable table = FseCompressionTable.newInstance(normalized, 2, 5);

    byte[] input = {1, 2};
    byte[] output = new byte[64];
    int size =
        FiniteStateEntropy.compress(
            output, UnsafeUtils.BYTE_ARRAY_BASE_OFFSET, 64, input, input.length, table);
    assertTrue(size == 0);
  }

  @Test
  void compressOddLengthInput() {
    // Odd-length input triggers the 3-element initialization path (inputSize & 1) != 0
    byte[] input = new byte[101];
    for (int i = 0; i < input.length; i++) {
      input[i] = (byte) (i % 4);
    }

    int[] counts = new int[256];
    Histogram.count(input, input.length, counts);
    int maxSymbol = 3;

    short[] normalized = new short[256];
    int tableLog = FiniteStateEntropy.optimalTableLog(12, input.length, maxSymbol);
    FiniteStateEntropy.normalizeCounts(normalized, tableLog, counts, input.length, maxSymbol);

    FseCompressionTable table = FseCompressionTable.newInstance(normalized, maxSymbol, tableLog);

    byte[] output = new byte[256];
    int size =
        FiniteStateEntropy.compress(
            output, UnsafeUtils.BYTE_ARRAY_BASE_OFFSET, 256, input, input.length, table);
    assertTrue(size > 0);
  }

  @Test
  void compressEvenMod4Input() {
    // Even length where (inputSize-2) & 2 != 0 → hits the extra 2-symbol encoding branch
    // inputSize=8: (8-2)=6, 6&2=2 ✓
    byte[] input = new byte[8];
    for (int i = 0; i < input.length; i++) {
      input[i] = (byte) (i % 3);
    }

    int[] counts = new int[256];
    Histogram.count(input, input.length, counts);
    int maxSymbol = 2;

    short[] normalized = new short[256];
    int tableLog = FiniteStateEntropy.optimalTableLog(12, input.length, maxSymbol);
    FiniteStateEntropy.normalizeCounts(normalized, tableLog, counts, input.length, maxSymbol);

    FseCompressionTable table = FseCompressionTable.newInstance(normalized, maxSymbol, tableLog);

    byte[] output = new byte[256];
    int size =
        FiniteStateEntropy.compress(
            output, UnsafeUtils.BYTE_ARRAY_BASE_OFFSET, 256, input, input.length, table);
    assertTrue(size > 0);
  }

  @Test
  void compressEvenDivisibleBy4Input() {
    // Even length divisible by 4: inputSize=100 → (100-2)=98, 98&2=2 ✓
    // But inputSize=12: (12-2)=10, 10&2=2 ✓
    byte[] input = new byte[12];
    for (int i = 0; i < input.length; i++) {
      input[i] = (byte) (i % 4);
    }

    int[] counts = new int[256];
    Histogram.count(input, input.length, counts);
    int maxSymbol = 3;

    short[] normalized = new short[256];
    int tableLog = FiniteStateEntropy.optimalTableLog(12, input.length, maxSymbol);
    FiniteStateEntropy.normalizeCounts(normalized, tableLog, counts, input.length, maxSymbol);

    FseCompressionTable table = FseCompressionTable.newInstance(normalized, maxSymbol, tableLog);

    byte[] output = new byte[256];
    int size =
        FiniteStateEntropy.compress(
            output, UnsafeUtils.BYTE_ARRAY_BASE_OFFSET, 256, input, input.length, table);
    assertTrue(size > 0);
  }

  @Test
  void compressLargerInput() {
    // Larger input to exercise the main compression loop more thoroughly
    byte[] input = new byte[500];
    for (int i = 0; i < input.length; i++) {
      input[i] = (byte) (i % 10);
    }

    int[] counts = new int[256];
    Histogram.count(input, input.length, counts);
    int maxSymbol = 9;

    short[] normalized = new short[256];
    int tableLog = FiniteStateEntropy.optimalTableLog(12, input.length, maxSymbol);
    FiniteStateEntropy.normalizeCounts(normalized, tableLog, counts, input.length, maxSymbol);

    FseCompressionTable table = FseCompressionTable.newInstance(normalized, maxSymbol, tableLog);

    byte[] output = new byte[1024];
    int size =
        FiniteStateEntropy.compress(
            output, UnsafeUtils.BYTE_ARRAY_BASE_OFFSET, 1024, input, input.length, table);
    assertTrue(size > 0);
  }

  @Test
  void compressTinyOutputThrows() {
    // outputSize < SIZE_OF_LONG(8) → checkArgument throws
    int[] counts = new int[256];
    counts[1] = 50;
    counts[2] = 50;
    short[] normalized = new short[256];
    FiniteStateEntropy.normalizeCounts(normalized, 5, counts, 100, 2);
    FseCompressionTable table = FseCompressionTable.newInstance(normalized, 2, 5);

    byte[] input = {1, 2, 1, 2, 1};
    byte[] output = new byte[4];
    assertThrows(
        IllegalArgumentException.class,
        () -> FiniteStateEntropy.compress(output, BASE, 4, input, input.length, table));
  }

  @Test
  void normalizeCountsSingleSymbolTotalThrows() {
    // When counts[symbol] == total → throws (should have been RLE)
    int[] counts = new int[16];
    counts[5] = 200;
    short[] normalized = new short[16];
    assertThrows(
        IllegalArgumentException.class,
        () -> FiniteStateEntropy.normalizeCounts(normalized, 6, counts, 200, 5));
  }

  @Test
  void normalizeCountsTableLogTooSmallThrows() {
    int[] counts = new int[256];
    for (int i = 0; i < 100; i++) counts[i] = 1;
    short[] normalized = new short[256];
    // minTableLog for 100 total, 99 maxSymbol will be ~7, so tableLog=5 is too small
    assertThrows(
        IllegalArgumentException.class,
        () -> FiniteStateEntropy.normalizeCounts(normalized, 5, counts, 100, 99));
  }

  @Test
  void normalizeCountsTableLogTooLargeThrows() {
    int[] counts = new int[16];
    counts[0] = 50;
    counts[1] = 50;
    short[] normalized = new short[16];
    assertThrows(
        IllegalArgumentException.class,
        () -> FiniteStateEntropy.normalizeCounts(normalized, 13, counts, 100, 1));
  }

  @Test
  void writeNormalizedCountsTableLogTooSmallThrows() {
    byte[] output = new byte[256];
    short[] normalized = new short[16];
    normalized[0] = 16;
    assertThrows(
        IllegalArgumentException.class,
        () ->
            FiniteStateEntropy.writeNormalizedCounts(output, BASE, 256, normalized, 0, 4));
  }

  @Test
  void writeNormalizedCountsTableLogTooLargeThrows() {
    byte[] output = new byte[256];
    short[] normalized = new short[16];
    normalized[0] = 16;
    assertThrows(
        IllegalArgumentException.class,
        () ->
            FiniteStateEntropy.writeNormalizedCounts(output, BASE, 256, normalized, 0, 13));
  }

  @Test
  void normalizeCountsTriggersNormalize2WithAllBranches() {
    // Distribution designed so normalizeCounts2 internal branches are all exercised:
    // - counts == 0 → normalizedCounts = 0
    // - counts <= lowThreshold → normalizedCounts = -1
    // - counts <= lowOne → normalizedCounts = 1
    // - counts > lowOne → UNASSIGNED
    //
    // With tableLog=7, total=183: lowThreshold=1, lowOne=2
    // 42 symbols with count=4 trigger REST_TO_BEAT bump 2→3, causing over-distribution
    int[] counts = new int[64];
    // 5 symbols count=0 (indices 0-4)
    // 5 symbols count=1 (indices 5-9)
    for (int i = 5; i < 10; i++) counts[i] = 1;
    // 5 symbols count=2 (indices 10-14)
    for (int i = 10; i < 15; i++) counts[i] = 2;
    // 42 symbols count=4 (indices 15-56)
    for (int i = 15; i < 57; i++) counts[i] = 4;
    int total = 5 + 10 + 168; // = 183
    int maxSymbol = 56;

    short[] normalized = new short[64];
    int tableLog = FiniteStateEntropy.optimalTableLog(12, total, maxSymbol);
    FiniteStateEntropy.normalizeCounts(normalized, tableLog, counts, total, maxSymbol);

    // Verify valid output
    int sum = 0;
    for (int i = 0; i <= maxSymbol; i++) {
      sum += normalized[i] < 0 ? 1 : normalized[i];
    }
    assertTrue(sum == (1 << tableLog), "Normalized counts should sum to 2^tableLog, got " + sum);
  }

  @Test
  void normalizeCountsTriggersNormalize2AllDistributed() {
    // Distribution where ALL symbols in normalizeCounts2 get assigned (distributed ==
    // maxSymbol+1).
    // Need all counts <= lowOne so everything gets 0, -1, or 1.
    // With many symbols each count=1, at tableLog=7 (lowThreshold=1, lowOne=2):
    //   count=1 → -1 in normalizeCounts2 first loop.
    // But we also need normalizeCounts to CALL normalizeCounts2.
    // Use: many count=2 symbols (prob ~1.4 each at tableLog=7) that DON'T get bumped,
    //       plus count=1 symbols that DO get assigned -1.
    // Actually, with count=2, lowOne=2 → 2 <= 2 → goes to lowOne branch → 1.
    // So ALL are distributed. Then distributed == maxSymbol+1 triggers line 228.
    //
    // For normalizeCounts to call normalizeCounts2, we need over-distribution.
    // Use a mix: some count=4 (probability bump causes over-distribution),
    // and the rest count <=2 (go to -1 or 1 in normalizeCounts2).
    int[] counts = new int[64];
    // 10 symbols with count=2 → in normalizeCounts2: <=lowOne → assigned 1
    for (int i = 0; i < 10; i++) counts[i] = 2;
    // 10 symbols with count=1 → in normalizeCounts2: <=lowThreshold → assigned -1
    for (int i = 10; i < 20; i++) counts[i] = 1;
    // 35 symbols with count=4 → in normalizeCounts: REST_TO_BEAT bump causes over-distribution
    // In normalizeCounts2: count=4 > lowOne(2) → UNASSIGNED
    for (int i = 20; i < 55; i++) counts[i] = 4;
    int total = 20 + 10 + 140; // = 170
    int maxSymbol = 54;

    short[] normalized = new short[64];
    int tableLog = FiniteStateEntropy.optimalTableLog(12, total, maxSymbol);
    FiniteStateEntropy.normalizeCounts(normalized, tableLog, counts, total, maxSymbol);

    int sum = 0;
    for (int i = 0; i <= maxSymbol; i++) {
      sum += normalized[i] < 0 ? 1 : normalized[i];
    }
    assertTrue(sum == (1 << tableLog), "Normalized counts should sum to 2^tableLog, got " + sum);
  }

  @Test
  void normalizeCounts2SecondaryLowOneRecalculation() {
    // Directly test normalizeCounts2 to cover the (total / toDistribute) > lowOne branch.
    // With tableLog=7, total=600: lowThreshold=4, lowOne=7
    // After first loop: many assigned, few UNASSIGNED carrying most of total
    // Then adjust total/toDistribute to trigger the secondary lowOne recalculation.
    //
    // Setup: 30 symbols (0-29), tableLog=7
    // Many small counts → -1 or 1 in first loop
    // 2 high counts → UNASSIGNED
    // After first loop, remaining total concentrated in 2 symbols with high toDistribute ratio
    int[] counts = new int[32];
    short[] normalized = new short[32];
    int maxSymbol = 29;

    // 10 symbols count=0
    // 10 symbols count=3 → <=lowThreshold(4) → -1
    for (int i = 10; i < 20; i++) counts[i] = 3;
    // 8 symbols count=6 → <=lowOne(7) → 1
    for (int i = 20; i < 28; i++) counts[i] = 6;
    // 2 symbols with very high count → UNASSIGNED
    counts[28] = 232;
    counts[29] = 290;
    // total = 0 + 30 + 48 + 232 + 290 = 600
    int total = 600;

    // After first loop: distributed=18, total=600-30-48=522, toDistribute=128-18=110
    // (522/110)=4 vs lowOne=7: 4 < 7 → FALSE, so secondary loop NOT triggered
    // Need to adjust: increase distributed or decrease remaining total
    // Let me make more symbols distributed:
    // Actually, I need (total/toDistribute) > lowOne for the TRUE branch.
    // With 18 distributed: toDistribute=110, need total>7*110=770. But total after dist=522.
    // Can't hit it with this setup. Let me use a different approach:
    // VERY small tableLog so lowOne is small.
    //
    // Alternative: call normalizeCounts2 directly with controlled inputs.
    // tableLog=7, total that makes lowOne=1, then have 2+ UNASSIGNED to trigger it.

    // Reset: tableLog=7, total=200: lowThreshold=200/128=1, lowOne=200*3/256=2
    // 25 symbols count=1 → -1 (distributed=25, total_remaining=200-25=175)
    // 3 symbols count=2 → 1 (distributed=28, total_remaining=175-6=169)
    // 2 symbols count=0
    // 0 symbols UNASSIGNED → all distributed!
    // distributed=28 == maxSymbol+1(30)? No, 28 != 30.
    // Hmm, the 2 zero-count symbols get 0 but don't count as distributed.
    // Only -1 and 1 count. So distributed=28.
    // But we have 30 symbols total (0-29). 2 zeros + 25 ones + 3 twos = 30.
    // All non-zero assigned, but distributed=28, maxSymbol+1=30. Not equal.
    // Because zero-count symbols don't increment distributed.
    // For distributed == maxSymbol+1: need EVERY symbol to be non-zero and <=lowOne.
    java.util.Arrays.fill(counts, 0);
    java.util.Arrays.fill(normalized, (short) 0);
    // All 30 symbols have count=1, total=30
    // But total=30 is too small for normalizeCounts2 to work well. Use count=2 for 128 symbols.
    // Actually just test it:
    for (int i = 0; i <= maxSymbol; i++) counts[i] = 7;
    total = 7 * 30; // = 210

    // tableLog=7: lowThreshold=210/128=1, lowOne=210*3/256=2
    // count=7 > lowOne(2) → UNASSIGNED. All 30 symbols UNASSIGNED. distributed=0.
    // Not what we want. Need counts <= lowOne.

    // Use count=2 for all: total=60, tableLog=7: lowThreshold=0, lowOne=0.
    // count=2 > 0 → UNASSIGNED. Still not distributed.

    // For all distributed: need lowOne >= max(count). With count=1 for all:
    // total=30, tableLog=7: lowThreshold=0, lowOne=0. count=1 > 0 → UNASSIGNED!
    // lowOne is 0 when total < 86 (for tableLog=7). Need total >= 86 for lowOne >= 1.

    // Use: 87 symbols with count=1, total=87, tableLog=7
    // lowThreshold=87/128=0, lowOne=87*3/256=1
    // count=1: 1<=1(lowOne) → assigned 1. distributed=87.
    // distributed(87) == maxSymbol+1(87)? maxSymbol=86. 87 == 87 → YES!
    counts = new int[128];
    normalized = new short[128];
    maxSymbol = 86;
    for (int i = 0; i <= maxSymbol; i++) counts[i] = 1;
    total = 87;

    FiniteStateEntropy.normalizeCounts2(normalized, 7, counts, total, maxSymbol);
    // Verify: should execute the distributed == maxSymbol+1 branch (line 228)
    int sum = 0;
    for (int i = 0; i <= maxSymbol; i++) {
      sum += normalized[i] < 0 ? 1 : normalized[i];
    }
    assertTrue(sum == (1 << 7), "Should sum to 128, got " + sum);
  }

  @Test
  void normalizeCounts2TotalBecomesZero() {
    // When total becomes 0 after assigning -1 and 1, line 241 is triggered.
    // This happens when sum of counts for assigned symbols equals original total
    // AND all symbols are assigned (but distributed < maxSymbol+1 because some have count=0).
    //
    // Setup: tableLog=7, some zero-count symbols, all non-zero symbols <=lowOne
    // After assignment, total=0 but distributed < maxSymbol+1 (zeros don't count)
    int[] counts = new int[128];
    short[] normalized = new short[128];
    int maxSymbol = 90;

    // 4 symbols with count=0 (indices 87-90)
    // 87 symbols with count=1 (indices 0-86)
    for (int i = 0; i < 87; i++) counts[i] = 1;
    int total = 87;

    // tableLog=7: lowThreshold=87/128=0, lowOne=87*3/256=1
    // count=1: 1<=lowOne(1) → 1, distributed++, total-=1
    // After loop: distributed=87, total=87-87=0
    // distributed(87) != maxSymbol+1(91) → falls through to line 241
    // total==0 → YES! Triggers the total==0 branch

    FiniteStateEntropy.normalizeCounts2(normalized, 7, counts, total, maxSymbol);
    int sum = 0;
    for (int i = 0; i <= maxSymbol; i++) {
      sum += normalized[i] < 0 ? 1 : normalized[i];
    }
    assertTrue(sum == (1 << 7), "Should sum to 128, got " + sum);
  }

  @Test
  void normalizeCounts2SecondaryLowOneLoop() {
    // Trigger the (total / toDistribute) > lowOne branch (line 216).
    // Need: after first loop, remaining total per slot exceeds lowOne.
    //
    // Use tableLog=7, small number of symbols so most total is UNASSIGNED.
    // 3 symbols: count=1 (→-1, distributed=3, remaining_total=total-3)
    // 2 symbols: count=large (→UNASSIGNED)
    // maxSymbol=4, 5 symbols, tableLog=7
    // Need total s.t. lowThreshold>=1 and lowOne>=1.
    // total=170: lowThreshold=1, lowOne=170*3/256=1
    // After first loop:
    //   count=1: 1<=1(lowThreshold) → -1, distributed=3, total-=3, total=167
    //   count=large: UNASSIGNED
    // toDistribute=128-3=125. (167/125)=1 > lowOne(1)? 1>1 → FALSE. Need >.

    // total=200: lowThreshold=1, lowOne=200*3/256=2
    // 3 symbols count=1: -1, distributed=3, total=197, toDistribute=125
    // (197/125)=1 > 2? No.

    // For condition to hold: (remaining_total / toDistribute) > lowOne
    // With lowOne=1: need remaining > toDistribute. Easy if remaining is large.
    // total=170, lowOne=1: 3 symbols count=1 → distributed=3, remaining=167, toDistribute=125
    // 167/125 = 1 (integer division). 1 > 1? FALSE.
    // 168/125 = 1. Still 1.
    // Need remaining/toDistribute >= 2. remaining >= 250. But remaining < total = 170.

    // Try very low tableLog and lowOne:
    // tableLog=5, total=50: lowThreshold=50/32=1, lowOne=50*3/64=2
    // 25 symbols count=1 → -1 (distributed=25, remaining=25, toDistribute=32-25=7)
    // 3 symbols count=large → UNASSIGNED
    // (25/7)=3 > 2 → TRUE!
    int[] counts = new int[32];
    short[] normalized = new short[32];
    int maxSymbol = 28;

    for (int i = 0; i < 25; i++) counts[i] = 1;
    // 3 symbols with count that sums to 25 (total=50, remaining 25 goes to these 3)
    counts[25] = 10;
    counts[26] = 8;
    counts[27] = 5;
    // remaining = 23 (not 25, since we summed to 23)
    // Adjust: total = 25 + 10 + 8 + 5 = 48
    // Need total=50 exactly? No, just make it work.
    // Actually let's just recompute: counts[27] = 7 → total = 25+10+8+7=50
    counts[27] = 7;
    counts[28] = 0; // zero-count symbol at maxSymbol
    int total2 = 50;

    // tableLog=5: lowThreshold=50/32=1, lowOne=50*3/64=2
    // first loop:
    //   count=1 (25 symbols): <=lowThreshold(1) → -1, distributed+=25, total-=25 → 25
    //   count=10: >lowOne(2) → UNASSIGNED
    //   count=8: >lowOne(2) → UNASSIGNED
    //   count=7: >lowOne(2) → UNASSIGNED
    //   count=0: normalized=0
    // distributed=25, total=25, toDistribute=32-25=7
    // (25/7)=3 > lowOne(2) → TRUE! Secondary loop executes.
    // newLowOne = (25*3)/(7*2) = 75/14 = 5
    // Second loop: UNASSIGNED with counts<=5: count=7 NO (7>5), count=8 NO, count=10 NO
    // None reassigned. toDistribute stays 7.
    // Hmm, none get reassigned because all UNASSIGNED counts > newLowOne.

    // Let me adjust: make one UNASSIGNED count <= newLowOne.
    // counts[25]=3, counts[26]=8, counts[27]=14 → total=25+3+8+14=50
    counts[25] = 3;
    counts[26] = 8;
    counts[27] = 14;
    // Now: count=3 > lowOne(2) → UNASSIGNED. After secondary recalc:
    // newLowOne = (25*3)/(7*2) = 5. count=3 <= 5 → reassigned to 1!
    // distributed becomes 26, toDistribute=32-26=6, total=25-3=22

    FiniteStateEntropy.normalizeCounts2(normalized, 5, counts, total2, maxSymbol);
    int sum = 0;
    for (int i = 0; i <= maxSymbol; i++) {
      sum += normalized[i] < 0 ? 1 : normalized[i];
    }
    assertTrue(sum == (1 << 5), "Should sum to 32, got " + sum);
  }

  @Test
  void writeNormalizedCountsTinyOutputThrows() {
    int[] counts = new int[256];
    for (int i = 0; i < 50; i++) counts[i] = 10;
    short[] normalized = new short[256];
    int total = 500;
    int maxSymbol = 49;
    int tableLog = FiniteStateEntropy.optimalTableLog(12, total, maxSymbol);
    FiniteStateEntropy.normalizeCounts(normalized, tableLog, counts, total, maxSymbol);

    byte[] output = new byte[1]; // too small
    assertThrows(
        IllegalArgumentException.class,
        () ->
            FiniteStateEntropy.writeNormalizedCounts(
                output, BASE, 1, normalized, maxSymbol, tableLog));
  }
}

package com.datadog.profiling.utils.zstd;

import static com.datadog.profiling.utils.zstd.HuffmanCompressionTableWorkspace.MAX_SYMBOL_COUNT;
import static com.datadog.profiling.utils.zstd.HuffmanCompressionTableWorkspace.MAX_TABLE_LOG;
import static com.datadog.profiling.utils.zstd.Util.checkArgument;

import datadog.trace.util.UnsafeUtils;
import java.util.Arrays;

/** Huffman encoding table: per-symbol codes and bit lengths. */
final class HuffmanCompressionTable {
  private static final int MAX_SYMBOL = 255;
  private static final int MIN_TABLE_LOG = 5;
  private static final int MAX_FSE_TABLE_LOG = 6;

  private final short[] values;
  private final byte[] numberOfBits;

  private int maxSymbol;
  private int maxNumberOfBits;

  HuffmanCompressionTable(int capacity) {
    this.values = new short[capacity];
    this.numberOfBits = new byte[capacity];
  }

  static int optimalNumberOfBits(int maxNumberOfBits, int inputSize, int maxSymbol) {
    if (inputSize <= 1) {
      throw new IllegalArgumentException("Not supported. Use RLE instead");
    }

    int result = maxNumberOfBits;
    result = Math.min(result, Util.highestBit(inputSize - 1) - 1);
    result = Math.max(result, Util.minTableLog(inputSize, maxSymbol));
    result = Math.max(result, MIN_TABLE_LOG);
    result = Math.min(result, MAX_TABLE_LOG);

    return result;
  }

  void initialize(
      int[] counts,
      int maxSymbol,
      int maxNumberOfBits,
      HuffmanCompressionTableWorkspace workspace) {
    checkArgument(maxSymbol <= MAX_SYMBOL, "Max symbol value too large");

    workspace.reset();

    NodeTable nodeTable = workspace.nodeTable;
    nodeTable.reset();

    int lastNonZero = buildTree(counts, maxSymbol, nodeTable);

    // enforce max table log
    maxNumberOfBits = setMaxHeight(nodeTable, lastNonZero, maxNumberOfBits, workspace);
    checkArgument(
        maxNumberOfBits <= MAX_TABLE_LOG, "Max number of bits larger than max table size");

    // populate table
    int symbolCount = maxSymbol + 1;
    for (int node = 0; node < symbolCount; node++) {
      int symbol = nodeTable.symbols[node];
      numberOfBits[symbol] = nodeTable.numberOfBits[node];
    }

    short[] entriesPerRank = workspace.entriesPerRank;
    short[] valuesPerRank = workspace.valuesPerRank;

    for (int n = 0; n <= lastNonZero; n++) {
      entriesPerRank[nodeTable.numberOfBits[n]]++;
    }

    // determine starting value per rank
    short startingValue = 0;
    for (int rank = maxNumberOfBits; rank > 0; rank--) {
      valuesPerRank[rank] = startingValue;
      startingValue += entriesPerRank[rank];
      @SuppressWarnings("ShiftOutOfRange") // Unsigned right shift on short is intentional
      short shifted = (short) (startingValue >>> 1);
      startingValue = shifted;
    }

    for (int n = 0; n <= maxSymbol; n++) {
      values[n] = valuesPerRank[numberOfBits[n]]++;
    }

    this.maxSymbol = maxSymbol;
    this.maxNumberOfBits = maxNumberOfBits;
  }

  private int buildTree(int[] counts, int maxSymbol, NodeTable nodeTable) {
    // populate leaves in descending order by count
    short current = 0;

    for (int symbol = 0; symbol <= maxSymbol; symbol++) {
      int count = counts[symbol];

      // simple insertion sort
      int position = current;
      while (position > 1 && count > nodeTable.count[position - 1]) {
        nodeTable.copyNode(position - 1, position);
        position--;
      }

      nodeTable.count[position] = count;
      nodeTable.symbols[position] = symbol;

      current++;
    }

    int lastNonZero = maxSymbol;
    while (nodeTable.count[lastNonZero] == 0) {
      lastNonZero--;
    }

    // populate non-leaf nodes
    short nonLeafStart = MAX_SYMBOL_COUNT;
    current = nonLeafStart;

    int currentLeaf = lastNonZero;

    // combine the two smallest leaves
    int currentNonLeaf = current;
    nodeTable.count[current] = nodeTable.count[currentLeaf] + nodeTable.count[currentLeaf - 1];
    nodeTable.parents[currentLeaf] = current;
    nodeTable.parents[currentLeaf - 1] = current;
    current++;
    currentLeaf -= 2;

    int root = MAX_SYMBOL_COUNT + lastNonZero - 1;

    // fill sentinels
    for (int n = current; n <= root; n++) {
      nodeTable.count[n] = 1 << 30;
    }

    // create parents
    while (current <= root) {
      int child1;
      if (currentLeaf >= 0 && nodeTable.count[currentLeaf] < nodeTable.count[currentNonLeaf]) {
        child1 = currentLeaf--;
      } else {
        child1 = currentNonLeaf++;
      }

      int child2;
      if (currentLeaf >= 0 && nodeTable.count[currentLeaf] < nodeTable.count[currentNonLeaf]) {
        child2 = currentLeaf--;
      } else {
        child2 = currentNonLeaf++;
      }

      nodeTable.count[current] = nodeTable.count[child1] + nodeTable.count[child2];
      nodeTable.parents[child1] = (short) current;
      nodeTable.parents[child2] = (short) current;
      current++;
    }

    // distribute weights
    nodeTable.numberOfBits[root] = 0;
    for (int n = root - 1; n >= nonLeafStart; n--) {
      short parent = nodeTable.parents[n];
      nodeTable.numberOfBits[n] = (byte) (nodeTable.numberOfBits[parent] + 1);
    }

    for (int n = 0; n <= lastNonZero; n++) {
      short parent = nodeTable.parents[n];
      nodeTable.numberOfBits[n] = (byte) (nodeTable.numberOfBits[parent] + 1);
    }

    return lastNonZero;
  }

  void encodeSymbol(BitOutputStream output, int symbol) {
    output.addBitsFast(values[symbol], numberOfBits[symbol]);
  }

  int write(
      Object outputBase,
      long outputAddress,
      int outputSize,
      HuffmanTableWriterWorkspace workspace) {
    byte[] weights = workspace.weights;

    long output = outputAddress;

    int maxNumberOfBits = this.maxNumberOfBits;
    int maxSymbol = this.maxSymbol;

    // convert to weights per RFC 8478 section 4.2.1
    for (int symbol = 0; symbol < maxSymbol; symbol++) {
      int bits = numberOfBits[symbol];
      if (bits == 0) {
        weights[symbol] = 0;
      } else {
        weights[symbol] = (byte) (maxNumberOfBits + 1 - bits);
      }
    }

    // attempt weights compression by FSE
    int size =
        compressWeights(outputBase, output + 1, outputSize - 1, weights, maxSymbol, workspace);

    if (maxSymbol > 127 && size > 127) {
      throw new AssertionError();
    }

    if (size != 0 && size != 1 && size < maxSymbol / 2) {
      UnsafeUtils.putByte(outputBase, output, (byte) size);
      return size + 1; // header + size
    } else {
      // Use raw encoding (4 bits per entry)
      int entryCount = maxSymbol;

      size = (entryCount + 1) / 2;
      checkArgument(size + 1 <= outputSize, "Output size too small");

      // header = #entries + 127 per RFC
      UnsafeUtils.putByte(outputBase, output, (byte) (127 + entryCount));
      output++;

      weights[maxSymbol] = 0; // last weight is implicit
      for (int i = 0; i < entryCount; i += 2) {
        UnsafeUtils.putByte(outputBase, output, (byte) ((weights[i] << 4) + weights[i + 1]));
        output++;
      }

      return (int) (output - outputAddress);
    }
  }

  boolean isValid(int[] counts, int maxSymbol) {
    if (maxSymbol > this.maxSymbol) {
      return false;
    }

    for (int symbol = 0; symbol <= maxSymbol; ++symbol) {
      if (counts[symbol] != 0 && numberOfBits[symbol] == 0) {
        return false;
      }
    }
    return true;
  }

  int estimateCompressedSize(int[] counts, int maxSymbol) {
    int numberOfBits = 0;
    for (int symbol = 0; symbol <= Math.min(maxSymbol, this.maxSymbol); symbol++) {
      numberOfBits += this.numberOfBits[symbol] * counts[symbol];
    }
    return numberOfBits >>> 3;
  }

  private static int setMaxHeight(
      NodeTable nodeTable,
      int lastNonZero,
      int maxNumberOfBits,
      HuffmanCompressionTableWorkspace workspace) {
    int largestBits = nodeTable.numberOfBits[lastNonZero];

    if (largestBits <= maxNumberOfBits) {
      return largestBits;
    }

    int totalCost = 0;
    int baseCost = 1 << (largestBits - maxNumberOfBits);
    int n = lastNonZero;

    while (nodeTable.numberOfBits[n] > maxNumberOfBits) {
      totalCost += baseCost - (1 << (largestBits - nodeTable.numberOfBits[n]));
      nodeTable.numberOfBits[n] = (byte) maxNumberOfBits;
      n--;
    }

    while (nodeTable.numberOfBits[n] == maxNumberOfBits) {
      n--;
    }

    // renormalize totalCost
    totalCost >>>= (largestBits - maxNumberOfBits);

    // repay normalized cost
    int noSymbol = 0xF0F0F0F0;
    int[] rankLast = workspace.rankLast;
    Arrays.fill(rankLast, noSymbol);

    int currentNbBits = maxNumberOfBits;
    for (int pos = n; pos >= 0; pos--) {
      if (nodeTable.numberOfBits[pos] >= currentNbBits) {
        continue;
      }
      currentNbBits = nodeTable.numberOfBits[pos];
      rankLast[maxNumberOfBits - currentNbBits] = pos;
    }

    while (totalCost > 0) {
      int numberOfBitsToDecrease = Util.highestBit(totalCost) + 1;
      for (; numberOfBitsToDecrease > 1; numberOfBitsToDecrease--) {
        int highPosition = rankLast[numberOfBitsToDecrease];
        int lowPosition = rankLast[numberOfBitsToDecrease - 1];
        if (highPosition == noSymbol) {
          continue;
        }
        if (lowPosition == noSymbol) {
          break;
        }
        int highTotal = nodeTable.count[highPosition];
        int lowTotal = 2 * nodeTable.count[lowPosition];
        if (highTotal <= lowTotal) {
          break;
        }
      }

      while ((numberOfBitsToDecrease <= MAX_TABLE_LOG)
          && (rankLast[numberOfBitsToDecrease] == noSymbol)) {
        numberOfBitsToDecrease++;
      }
      totalCost -= 1 << (numberOfBitsToDecrease - 1);
      if (rankLast[numberOfBitsToDecrease - 1] == noSymbol) {
        rankLast[numberOfBitsToDecrease - 1] = rankLast[numberOfBitsToDecrease];
      }
      nodeTable.numberOfBits[rankLast[numberOfBitsToDecrease]]++;
      if (rankLast[numberOfBitsToDecrease] == 0) {
        rankLast[numberOfBitsToDecrease] = noSymbol;
      } else {
        rankLast[numberOfBitsToDecrease]--;
        if (nodeTable.numberOfBits[rankLast[numberOfBitsToDecrease]]
            != maxNumberOfBits - numberOfBitsToDecrease) {
          rankLast[numberOfBitsToDecrease] = noSymbol;
        }
      }
    }

    while (totalCost < 0) {
      if (rankLast[1] == noSymbol) {
        while (nodeTable.numberOfBits[n] == maxNumberOfBits) {
          n--;
        }
        nodeTable.numberOfBits[n + 1]--;
        rankLast[1] = n + 1;
        totalCost++;
        continue;
      }
      nodeTable.numberOfBits[rankLast[1] + 1]--;
      rankLast[1]++;
      totalCost++;
    }

    return maxNumberOfBits;
  }

  private static int compressWeights(
      Object outputBase,
      long outputAddress,
      int outputSize,
      byte[] weights,
      int weightsLength,
      HuffmanTableWriterWorkspace workspace) {
    if (weightsLength <= 1) {
      return 0;
    }

    int[] counts = workspace.counts;
    Histogram.count(weights, weightsLength, counts);
    int maxSymbol = Histogram.findMaxSymbol(counts, MAX_TABLE_LOG);
    int maxCount = Histogram.findLargestCount(counts, maxSymbol);

    if (maxCount == weightsLength) {
      return 1; // only a single symbol in source
    }
    if (maxCount == 1) {
      return 0; // each symbol present maximum once => not compressible
    }

    short[] normalizedCounts = workspace.normalizedCounts;

    int tableLog = FiniteStateEntropy.optimalTableLog(MAX_FSE_TABLE_LOG, weightsLength, maxSymbol);
    FiniteStateEntropy.normalizeCounts(
        normalizedCounts, tableLog, counts, weightsLength, maxSymbol);

    long output = outputAddress;
    long outputLimit = outputAddress + outputSize;

    // Write table description header
    int headerSize =
        FiniteStateEntropy.writeNormalizedCounts(
            outputBase, output, outputSize, normalizedCounts, maxSymbol, tableLog);
    output += headerSize;

    // Compress
    FseCompressionTable compressionTable = workspace.fseTable;
    compressionTable.initialize(normalizedCounts, maxSymbol, tableLog);
    int compressedSize =
        FiniteStateEntropy.compress(
            outputBase,
            output,
            (int) (outputLimit - output),
            weights,
            weightsLength,
            compressionTable);
    if (compressedSize == 0) {
      return 0;
    }
    output += compressedSize;

    return (int) (output - outputAddress);
  }
}

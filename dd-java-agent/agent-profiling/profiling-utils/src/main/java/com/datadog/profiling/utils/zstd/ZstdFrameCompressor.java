package com.datadog.profiling.utils.zstd;

import static com.datadog.profiling.utils.zstd.Util.checkArgument;
import static com.datadog.profiling.utils.zstd.Util.put24BitLittleEndian;
import static com.datadog.profiling.utils.zstd.ZstdConstants.COMPRESSED_BLOCK;
import static com.datadog.profiling.utils.zstd.ZstdConstants.COMPRESSED_LITERALS_BLOCK;
import static com.datadog.profiling.utils.zstd.ZstdConstants.MAGIC_NUMBER;
import static com.datadog.profiling.utils.zstd.ZstdConstants.MIN_BLOCK_SIZE;
import static com.datadog.profiling.utils.zstd.ZstdConstants.MIN_WINDOW_LOG;
import static com.datadog.profiling.utils.zstd.ZstdConstants.RAW_BLOCK;
import static com.datadog.profiling.utils.zstd.ZstdConstants.RAW_LITERALS_BLOCK;
import static com.datadog.profiling.utils.zstd.ZstdConstants.RLE_LITERALS_BLOCK;
import static com.datadog.profiling.utils.zstd.ZstdConstants.SIZE_OF_BLOCK_HEADER;
import static com.datadog.profiling.utils.zstd.ZstdConstants.SIZE_OF_INT;
import static com.datadog.profiling.utils.zstd.ZstdConstants.SIZE_OF_SHORT;
import static com.datadog.profiling.utils.zstd.ZstdConstants.TREELESS_LITERALS_BLOCK;

import datadog.trace.util.UnsafeUtils;

/** Orchestrates zstd frame compression: magic, header, block loop, checksum. */
final class ZstdFrameCompressor {
  static final int MAX_FRAME_HEADER_SIZE = 14;

  private static final int CHECKSUM_FLAG = 0b100;
  private static final int SINGLE_SEGMENT_FLAG = 0b100000;

  private static final int MINIMUM_LITERALS_SIZE = 63;
  private static final int MAX_HUFFMAN_TABLE_LOG = 11;
  private static final int MAX_SYMBOL = 255;
  private static final int MAX_SYMBOL_COUNT = 256;

  private static final BlockCompressor DFAST_COMPRESSOR = new DoubleFastBlockCompressor();

  private ZstdFrameCompressor() {}

  static int writeMagic(Object outputBase, long outputAddress, long outputLimit) {
    checkArgument(outputLimit - outputAddress >= SIZE_OF_INT, "Output buffer too small");
    UnsafeUtils.putInt(outputBase, outputAddress, MAGIC_NUMBER);
    return SIZE_OF_INT;
  }

  static int writeFrameHeader(
      Object outputBase, long outputAddress, long outputLimit, int inputSize, int windowSize) {
    checkArgument(outputLimit - outputAddress >= MAX_FRAME_HEADER_SIZE, "Output buffer too small");

    long output = outputAddress;

    int contentSizeDescriptor = 0;
    if (inputSize != -1) {
      contentSizeDescriptor = (inputSize >= 256 ? 1 : 0) + (inputSize >= 65536 + 256 ? 1 : 0);
    }
    int frameHeaderDescriptor = (contentSizeDescriptor << 6) | CHECKSUM_FLAG;

    boolean singleSegment = inputSize != -1 && windowSize >= inputSize;
    if (singleSegment) {
      frameHeaderDescriptor |= SINGLE_SEGMENT_FLAG;
    }

    UnsafeUtils.putByte(outputBase, output, (byte) frameHeaderDescriptor);
    output++;

    if (!singleSegment) {
      int base = Integer.highestOneBit(windowSize);
      int exponent = 32 - Integer.numberOfLeadingZeros(base) - 1;
      if (exponent < MIN_WINDOW_LOG) {
        throw new IllegalArgumentException("Minimum window size is " + (1 << MIN_WINDOW_LOG));
      }

      int remainder = windowSize - base;
      if (remainder % (base / 8) != 0) {
        throw new IllegalArgumentException(
            "Window size of magnitude 2^" + exponent + " must be multiple of " + (base / 8));
      }

      int mantissa = remainder / (base / 8);
      int encoded = ((exponent - MIN_WINDOW_LOG) << 3) | mantissa;

      UnsafeUtils.putByte(outputBase, output, (byte) encoded);
      output++;
    }

    switch (contentSizeDescriptor) {
      case 0:
        if (singleSegment) {
          UnsafeUtils.putByte(outputBase, output++, (byte) inputSize);
        }
        break;
      case 1:
        UnsafeUtils.putShort(outputBase, output, (short) (inputSize - 256));
        output += SIZE_OF_SHORT;
        break;
      case 2:
        UnsafeUtils.putInt(outputBase, output, inputSize);
        output += SIZE_OF_INT;
        break;
      default:
        throw new AssertionError();
    }

    return (int) (output - outputAddress);
  }

  static int writeChecksum(
      Object outputBase,
      long outputAddress,
      long outputLimit,
      Object inputBase,
      long inputAddress,
      long inputLimit) {
    checkArgument(outputLimit - outputAddress >= SIZE_OF_INT, "Output buffer too small");

    int inputSize = (int) (inputLimit - inputAddress);
    long hash = XxHash64.hash(0, inputBase, inputAddress, inputSize);
    UnsafeUtils.putInt(outputBase, outputAddress, (int) hash);

    return SIZE_OF_INT;
  }

  @SuppressWarnings("PointlessBitwiseExpression") // Bit operations explicit for clarity
  static int writeCompressedBlock(
      Object inputBase,
      long input,
      int blockSize,
      Object outputBase,
      long output,
      int outputSize,
      CompressionContext context,
      boolean lastBlock) {
    int compressedSize = 0;
    if (blockSize > 0) {
      compressedSize =
          compressBlock(
              inputBase,
              input,
              blockSize,
              outputBase,
              output + SIZE_OF_BLOCK_HEADER,
              outputSize - SIZE_OF_BLOCK_HEADER,
              context);
    }

    if (compressedSize == 0) { // block is not compressible
      checkArgument(blockSize + SIZE_OF_BLOCK_HEADER <= outputSize, "Output size too small");

      int blockHeader = (lastBlock ? 1 : 0) | (RAW_BLOCK << 1) | (blockSize << 3);
      put24BitLittleEndian(outputBase, output, blockHeader);
      UnsafeUtils.copyMemory(
          inputBase, input, outputBase, output + SIZE_OF_BLOCK_HEADER, blockSize);
      compressedSize = SIZE_OF_BLOCK_HEADER + blockSize;
    } else {
      int blockHeader = (lastBlock ? 1 : 0) | (COMPRESSED_BLOCK << 1) | (compressedSize << 3);
      put24BitLittleEndian(outputBase, output, blockHeader);
      compressedSize += SIZE_OF_BLOCK_HEADER;
    }
    return compressedSize;
  }

  private static int compressBlock(
      Object inputBase,
      long inputAddress,
      int inputSize,
      Object outputBase,
      long outputAddress,
      int outputSize,
      CompressionContext context) {
    if (inputSize < MIN_BLOCK_SIZE + SIZE_OF_BLOCK_HEADER + 1) {
      return 0;
    }

    CompressionParameters parameters = context.parameters;
    context.blockCompressionState.enforceMaxDistance(
        inputAddress + inputSize, parameters.getWindowSize());
    context.sequenceStore.reset();

    int lastLiteralsSize =
        DFAST_COMPRESSOR.compressBlock(
            inputBase,
            inputAddress,
            inputSize,
            context.sequenceStore,
            context.blockCompressionState,
            context.offsets,
            parameters);

    long lastLiteralsAddress = inputAddress + inputSize - lastLiteralsSize;

    // append trailing literals
    context.sequenceStore.appendLiterals(inputBase, lastLiteralsAddress, lastLiteralsSize);

    // convert length/offsets into codes
    context.sequenceStore.generateCodes();

    long outputLimit = outputAddress + outputSize;
    long output = outputAddress;

    int compressedLiteralsSize =
        encodeLiterals(
            context.huffmanContext,
            parameters,
            outputBase,
            output,
            (int) (outputLimit - output),
            context.sequenceStore.literalsBuffer,
            context.sequenceStore.literalsLength);
    output += compressedLiteralsSize;

    int compressedSequencesSize =
        SequenceEncoder.compressSequences(
            outputBase,
            output,
            (int) (outputLimit - output),
            context.sequenceStore,
            parameters.getStrategy(),
            context.sequenceEncodingContext);

    int compressedSize = compressedLiteralsSize + compressedSequencesSize;
    if (compressedSize == 0) {
      return 0;
    }

    // Check compressibility
    int maxCompressedSize = inputSize - calculateMinimumGain(inputSize, parameters.getStrategy());
    if (compressedSize > maxCompressedSize) {
      return 0;
    }

    // confirm repeated offsets and entropy tables
    context.commit();

    return compressedSize;
  }

  @SuppressWarnings("ShiftOutOfRange") // Unsigned right shift with byte cast is intentional
  static int encodeLiterals(
      HuffmanCompressionContext context,
      CompressionParameters parameters,
      Object outputBase,
      long outputAddress,
      int outputSize,
      byte[] literals,
      int literalsSize) {
    boolean bypassCompression =
        (parameters.getStrategy() == CompressionParameters.Strategy.FAST)
            && (parameters.getTargetLength() > 0);
    if (bypassCompression || literalsSize <= MINIMUM_LITERALS_SIZE) {
      return rawLiterals(
          outputBase,
          outputAddress,
          outputSize,
          literals,
          UnsafeUtils.BYTE_ARRAY_BASE_OFFSET,
          literalsSize);
    }

    int headerSize = 3 + (literalsSize >= 1024 ? 1 : 0) + (literalsSize >= 16384 ? 1 : 0);

    checkArgument(headerSize + 1 <= outputSize, "Output buffer too small");

    int[] counts = new int[MAX_SYMBOL_COUNT];
    Histogram.count(literals, literalsSize, counts);
    int maxSymbol = Histogram.findMaxSymbol(counts, MAX_SYMBOL);
    int largestCount = Histogram.findLargestCount(counts, maxSymbol);

    long literalsAddress = UnsafeUtils.BYTE_ARRAY_BASE_OFFSET;
    if (largestCount == literalsSize) {
      return rleLiterals(
          outputBase,
          outputAddress,
          outputSize,
          literals,
          UnsafeUtils.BYTE_ARRAY_BASE_OFFSET,
          literalsSize);
    } else if (largestCount <= (literalsSize >>> 7) + 4) {
      return rawLiterals(
          outputBase,
          outputAddress,
          outputSize,
          literals,
          UnsafeUtils.BYTE_ARRAY_BASE_OFFSET,
          literalsSize);
    }

    HuffmanCompressionTable previousTable = context.getPreviousTable();
    HuffmanCompressionTable table;
    int serializedTableSize;
    boolean reuseTable;

    boolean canReuse = previousTable.isValid(counts, maxSymbol);

    boolean preferReuse =
        parameters.getStrategy().ordinal() < CompressionParameters.Strategy.LAZY.ordinal()
            && literalsSize <= 1024;
    if (preferReuse && canReuse) {
      table = previousTable;
      reuseTable = true;
      serializedTableSize = 0;
    } else {
      HuffmanCompressionTable newTable = context.borrowTemporaryTable();

      newTable.initialize(
          counts,
          maxSymbol,
          HuffmanCompressionTable.optimalNumberOfBits(
              MAX_HUFFMAN_TABLE_LOG, literalsSize, maxSymbol),
          context.getCompressionTableWorkspace());

      serializedTableSize =
          newTable.write(
              outputBase,
              outputAddress + headerSize,
              outputSize - headerSize,
              context.getTableWriterWorkspace());

      if (canReuse
          && previousTable.estimateCompressedSize(counts, maxSymbol)
              <= serializedTableSize + newTable.estimateCompressedSize(counts, maxSymbol)) {
        table = previousTable;
        reuseTable = true;
        serializedTableSize = 0;
        context.discardTemporaryTable();
      } else {
        table = newTable;
        reuseTable = false;
      }
    }

    int compressedSize;
    boolean singleStream = literalsSize < 256;
    if (singleStream) {
      compressedSize =
          HuffmanCompressor.compressSingleStream(
              outputBase,
              outputAddress + headerSize + serializedTableSize,
              outputSize - headerSize - serializedTableSize,
              literals,
              literalsAddress,
              literalsSize,
              table);
    } else {
      compressedSize =
          HuffmanCompressor.compress4streams(
              outputBase,
              outputAddress + headerSize + serializedTableSize,
              outputSize - headerSize - serializedTableSize,
              literals,
              literalsAddress,
              literalsSize,
              table);
    }

    int totalSize = serializedTableSize + compressedSize;
    int minimumGain = calculateMinimumGain(literalsSize, parameters.getStrategy());

    if (compressedSize == 0 || totalSize >= literalsSize - minimumGain) {
      context.discardTemporaryTable();
      return rawLiterals(
          outputBase,
          outputAddress,
          outputSize,
          literals,
          UnsafeUtils.BYTE_ARRAY_BASE_OFFSET,
          literalsSize);
    }

    int encodingType = reuseTable ? TREELESS_LITERALS_BLOCK : COMPRESSED_LITERALS_BLOCK;

    switch (headerSize) {
      case 3:
        {
          int header =
              encodingType
                  | ((singleStream ? 0 : 1) << 2)
                  | (literalsSize << 4)
                  | (totalSize << 14);
          put24BitLittleEndian(outputBase, outputAddress, header);
          break;
        }
      case 4:
        {
          int header = encodingType | (2 << 2) | (literalsSize << 4) | (totalSize << 18);
          UnsafeUtils.putInt(outputBase, outputAddress, header);
          break;
        }
      case 5:
        {
          int header = encodingType | (3 << 2) | (literalsSize << 4) | (totalSize << 22);
          UnsafeUtils.putInt(outputBase, outputAddress, header);
          UnsafeUtils.putByte(outputBase, outputAddress + SIZE_OF_INT, (byte) (totalSize >>> 10));
          break;
        }
      default:
        throw new IllegalStateException();
    }

    return headerSize + totalSize;
  }

  private static int rleLiterals(
      Object outputBase,
      long outputAddress,
      int outputSize,
      Object inputBase,
      long inputAddress,
      int inputSize) {
    int headerSize = 1 + (inputSize > 31 ? 1 : 0) + (inputSize > 4095 ? 1 : 0);

    switch (headerSize) {
      case 1:
        UnsafeUtils.putByte(
            outputBase, outputAddress, (byte) (RLE_LITERALS_BLOCK | (inputSize << 3)));
        break;
      case 2:
        UnsafeUtils.putShort(
            outputBase, outputAddress, (short) (RLE_LITERALS_BLOCK | (1 << 2) | (inputSize << 4)));
        break;
      case 3:
        UnsafeUtils.putInt(
            outputBase, outputAddress, RLE_LITERALS_BLOCK | (3 << 2) | (inputSize << 4));
        break;
      default:
        throw new IllegalStateException();
    }

    UnsafeUtils.putByte(
        outputBase, outputAddress + headerSize, UnsafeUtils.getByte(inputBase, inputAddress));

    return headerSize + 1;
  }

  private static int calculateMinimumGain(int inputSize, CompressionParameters.Strategy strategy) {
    int minLog = strategy == CompressionParameters.Strategy.BTULTRA ? 7 : 6;
    return (inputSize >>> minLog) + 2;
  }

  private static int rawLiterals(
      Object outputBase,
      long outputAddress,
      int outputSize,
      Object inputBase,
      long inputAddress,
      int inputSize) {
    int headerSize = 1;
    if (inputSize >= 32) {
      headerSize++;
    }
    if (inputSize >= 4096) {
      headerSize++;
    }

    checkArgument(inputSize + headerSize <= outputSize, "Output buffer too small");

    switch (headerSize) {
      case 1:
        UnsafeUtils.putByte(
            outputBase, outputAddress, (byte) (RAW_LITERALS_BLOCK | (inputSize << 3)));
        break;
      case 2:
        UnsafeUtils.putShort(
            outputBase, outputAddress, (short) (RAW_LITERALS_BLOCK | (1 << 2) | (inputSize << 4)));
        break;
      case 3:
        put24BitLittleEndian(
            outputBase, outputAddress, RAW_LITERALS_BLOCK | (3 << 2) | (inputSize << 4));
        break;
      default:
        throw new AssertionError();
    }

    checkArgument(inputSize + 1 <= outputSize, "Output buffer too small");

    UnsafeUtils.copyMemory(
        inputBase, inputAddress, outputBase, outputAddress + headerSize, inputSize);

    return headerSize + inputSize;
  }
}

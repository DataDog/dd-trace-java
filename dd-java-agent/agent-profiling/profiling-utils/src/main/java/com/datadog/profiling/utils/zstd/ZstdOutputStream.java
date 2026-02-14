package com.datadog.profiling.utils.zstd;

import static com.datadog.profiling.utils.zstd.CompressionParameters.DEFAULT_COMPRESSION_LEVEL;
import static com.datadog.profiling.utils.zstd.Util.checkState;
import static com.datadog.profiling.utils.zstd.Util.put24BitLittleEndian;
import static com.datadog.profiling.utils.zstd.ZstdConstants.SIZE_OF_BLOCK_HEADER;
import static com.datadog.profiling.utils.zstd.ZstdConstants.SIZE_OF_LONG;
import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.util.Objects.requireNonNull;

import datadog.trace.util.UnsafeUtils;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;

/**
 * Streaming zstd compressor. Buffers input and compresses in blocks.
 *
 * <p><b>Important:</b> This implementation deviates from the standard {@link OutputStream} contract
 * for {@link #flush()}. Due to zstd's sliding window compression design, flush() only writes
 * compressed data when the buffer exceeds approximately 1.25 MB. Data below this threshold remains
 * buffered until {@link #close()} is called. Always call {@link #close()} to ensure all data is
 * written and the zstd frame is finalized.
 *
 * <p>Compression level is fixed at level 3 (DFAST strategy). Higher compression levels (6-22, LAZY
 * and above) are not yet implemented.
 */
public class ZstdOutputStream extends OutputStream {
  private static final int MAX_BUFFER_SIZE_LIMIT = 16 * 1024 * 1024; // 16 MB safety limit
  private static final int MAX_POOLED_BUFFER_SIZE = 2 * 1024 * 1024; // 2 MB pooling limit
  private static final int LAZY_INIT_THRESHOLD = 8 * 1024; // 8KB - defer context allocation

  // Thread-local buffer pool to reduce GC pressure
  private static final ThreadLocal<byte[]> BUFFER_POOL =
      ThreadLocal.withInitial(() -> new byte[128 * 1024]);

  private final OutputStream outputStream;
  private final CompressionParameters parameters;
  private CompressionContext context; // Nullable until LAZY_INIT_THRESHOLD crossed
  private final int maxBufferSize;

  private XxHash64 partialHash;

  private byte[] uncompressed;
  private final byte[] compressed;

  private int uncompressedOffset;
  private int uncompressedPosition;
  private int firstWriteSize = -1; // Track first write for pre-sizing

  private boolean closed;

  public ZstdOutputStream(OutputStream outputStream) throws IOException {
    this(outputStream, -1);
  }

  /**
   * Creates a new ZstdOutputStream with optional size hint for optimization.
   *
   * @param outputStream the output stream to write compressed data to
   * @param estimatedSize estimated total input size in bytes, or -1 if unknown.
   *                     Used to optimize block size for small files.
   */
  public ZstdOutputStream(OutputStream outputStream, int estimatedSize) throws IOException {
    this(outputStream, estimatedSize, DEFAULT_COMPRESSION_LEVEL);
  }

  /**
   * Creates a new ZstdOutputStream with custom compression level.
   *
   * @param outputStream the output stream to write compressed data to
   * @param estimatedSize estimated total input size in bytes, or -1 if unknown.
   * @param compressionLevel compression level (1-5): 1-2=FAST, 3-4=DFAST, 5=GREEDY
   */
  public ZstdOutputStream(OutputStream outputStream, int estimatedSize, int compressionLevel) throws IOException {
    this.outputStream = requireNonNull(outputStream, "outputStream is null");
    this.parameters = CompressionParameters.compute(compressionLevel, estimatedSize);
    if (parameters.getStrategy().ordinal() >= CompressionParameters.Strategy.LAZY.ordinal()) {
      throw new IllegalArgumentException(
          "Compression strategies LAZY and above (levels 6-22) are not yet implemented. "
              + "Use compression levels 1-5 (FAST or DFAST strategies).");
    }
    // Defer context allocation until LAZY_INIT_THRESHOLD crossed (Phase 1)
    this.context = null;
    this.maxBufferSize = min(parameters.getWindowSize() * 4, MAX_BUFFER_SIZE_LIMIT);

    // Use pooled buffer to reduce allocation pressure
    this.uncompressed = BUFFER_POOL.get();

    // Use adaptive block sizing based on estimated input size (Phase 4)
    int optimalBlockSize = CompressionParameters.getOptimalBlockSize(estimatedSize);
    int bufferSize = max(parameters.getBlockSize(), optimalBlockSize) + SIZE_OF_BLOCK_HEADER;
    this.compressed = new byte[bufferSize + (bufferSize >>> 8) + SIZE_OF_LONG];
  }

  @Override
  public void write(int b) throws IOException {
    if (closed) {
      throw new IOException("Stream is closed");
    }

    growBufferIfNecessary(1);
    uncompressed[uncompressedPosition++] = (byte) b;

    // Only check compression when buffer is full
    if (uncompressedPosition == uncompressed.length) {
      ensureContextInitialized();
      if (context != null
          && uncompressed.length >= maxBufferSize
          && uncompressed.length - context.parameters.getWindowSize()
              > context.parameters.getBlockSize()) {
        writeChunk(false);
      }
    }
  }

  @Override
  public void write(byte[] buffer) throws IOException {
    write(buffer, 0, buffer.length);
  }

  @Override
  public void write(byte[] buffer, int offset, int length) throws IOException {
    if (buffer == null) {
      throw new NullPointerException("buffer is null");
    }
    if (offset < 0 || length < 0 || length > buffer.length - offset) {
      throw new IndexOutOfBoundsException(
          "offset=" + offset + ", length=" + length + ", buffer.length=" + buffer.length);
    }
    if (closed) {
      throw new IOException("Stream is closed");
    }

    // Pre-size buffer based on first write (heuristic: 3× works well for profiler batch uploads)
    // For profiler use case: first write ≈ total size (single batch upload)
    if (firstWriteSize == -1 && length > 0) {
      firstWriteSize = length;
      int estimatedTotal = min(length * 3, maxBufferSize);
      if (estimatedTotal > uncompressed.length) {
        uncompressed = Arrays.copyOf(uncompressed, estimatedTotal);
      }
    }

    growBufferIfNecessary(length);

    while (length > 0) {
      int writeSize = min(length, uncompressed.length - uncompressedPosition);
      System.arraycopy(buffer, offset, uncompressed, uncompressedPosition, writeSize);

      uncompressedPosition += writeSize;
      length -= writeSize;
      offset += writeSize;

      // Only check compression when buffer is actually full (Solution 1 optimization)
      if (uncompressedPosition == uncompressed.length) {
        ensureContextInitialized();
        if (context != null
            && uncompressed.length >= maxBufferSize
            && uncompressed.length - context.parameters.getWindowSize()
                > context.parameters.getBlockSize()) {
          writeChunk(false);
        }
      }
    }
  }

  private void ensureContextInitialized() {
    if (context == null && uncompressedPosition >= LAZY_INIT_THRESHOLD) {
      context = new CompressionContext(parameters, UnsafeUtils.BYTE_ARRAY_BASE_OFFSET, Integer.MAX_VALUE);
    }
  }

  private void growBufferIfNecessary(int length) {
    if (uncompressedPosition + length <= uncompressed.length
        || uncompressed.length >= maxBufferSize) {
      return;
    }

    // Use long arithmetic to prevent integer overflow for large lengths
    long targetSize = ((long) uncompressed.length + length) * 2;
    int newSize = (int) min(targetSize, maxBufferSize);
    newSize = max(newSize, parameters.getBlockSize());
    uncompressed = Arrays.copyOf(uncompressed, newSize);
  }

  /**
   * Flushes this output stream.
   *
   * <p><b>Important:</b> Unlike standard {@link OutputStream#flush()}, this method only writes
   * compressed data if the buffer contains more than approximately 1.25 MB (windowSize + 2 *
   * blockSize). Smaller amounts of buffered data remain unwritten. This is necessary due to zstd's
   * sliding window design which requires maintaining a history window for backreferences.
   *
   * <p>Always call {@link #close()} to ensure all buffered data is written and the zstd frame is
   * properly finalized with checksum.
   */
  @Override
  public void flush() throws IOException {
    if (!closed && uncompressedPosition > uncompressedOffset) {
      ensureContextInitialized(); // Initialize if not already done
      if (context != null) {
        int bufferedSize = uncompressedPosition - uncompressedOffset;
        int minimumSize = context.parameters.getWindowSize() + 2 * context.parameters.getBlockSize();
        if (bufferedSize > minimumSize) {
          writeChunk(false);
        }
      }
    }
    outputStream.flush();
  }

  @Override
  public void close() throws IOException {
    if (!closed) {
      try {
        // For very small data (< 8KB), use raw block mode to avoid context allocation
        if (context == null && uncompressedPosition < LAZY_INIT_THRESHOLD) {
          writeRawFrame();
        } else {
          ensureContextInitialized(); // Initialize if needed for larger data
          writeChunk(true);
        }
      } finally {
        closed = true;
        // Recycle buffer if within pooling size limit
        if (uncompressed != null && uncompressed.length <= MAX_POOLED_BUFFER_SIZE) {
          BUFFER_POOL.set(uncompressed);
        }
        outputStream.close();
      }
    }
  }

  private void writeRawFrame() throws IOException {
    long baseOffset = UnsafeUtils.BYTE_ARRAY_BASE_OFFSET;
    int outputAddress = (int) baseOffset;

    // Write magic number
    outputAddress += ZstdFrameCompressor.writeMagic(compressed, outputAddress, outputAddress + 4);

    // Write frame header
    outputAddress +=
        ZstdFrameCompressor.writeFrameHeader(
            compressed,
            outputAddress,
            outputAddress + 14,
            uncompressedPosition,
            parameters.getWindowSize());

    outputStream.write(compressed, 0, (int) (outputAddress - baseOffset));

    // Write single raw block (even if empty)
    int blockHeader = (1 << 0) | (0 << 1) | (uncompressedPosition << 3); // last=1, type=RAW
    put24BitLittleEndian(compressed, baseOffset, blockHeader);
    outputStream.write(compressed, 0, SIZE_OF_BLOCK_HEADER);
    if (uncompressedPosition > 0) {
      outputStream.write(uncompressed, 0, uncompressedPosition);
    }

    // Write checksum
    XxHash64 hash = new XxHash64();
    if (uncompressedPosition > 0) {
      hash.update(uncompressed, 0, uncompressedPosition);
    }
    int checksum = (int) hash.hash();
    outputStream.write(checksum);
    outputStream.write(checksum >> 8);
    outputStream.write(checksum >> 16);
    outputStream.write(checksum >> 24);
  }

  private void writeChunk(boolean lastChunk) throws IOException {
    long baseOffset = UnsafeUtils.BYTE_ARRAY_BASE_OFFSET;

    int chunkSize;
    if (lastChunk) {
      chunkSize = uncompressedPosition - uncompressedOffset;
    } else {
      int blockSize = context.parameters.getBlockSize();
      chunkSize =
          uncompressedPosition
              - uncompressedOffset
              - context.parameters.getWindowSize()
              - blockSize;
      checkState(chunkSize > blockSize, "Must write at least one full block");
      chunkSize = (chunkSize / blockSize) * blockSize;
    }

    if (partialHash == null) {
      partialHash = new XxHash64();

      int inputSize = lastChunk ? chunkSize : -1;

      int outputAddress = (int) baseOffset;
      outputAddress += ZstdFrameCompressor.writeMagic(compressed, outputAddress, outputAddress + 4);
      outputAddress +=
          ZstdFrameCompressor.writeFrameHeader(
              compressed,
              outputAddress,
              outputAddress + 14,
              inputSize,
              context.parameters.getWindowSize());
      outputStream.write(compressed, 0, (int) (outputAddress - baseOffset));
    }

    partialHash.update(uncompressed, uncompressedOffset, chunkSize);

    do {
      int blockSize = min(chunkSize, context.parameters.getBlockSize());
      int compressedSize =
          ZstdFrameCompressor.writeCompressedBlock(
              uncompressed,
              baseOffset + uncompressedOffset,
              blockSize,
              compressed,
              baseOffset,
              compressed.length,
              context,
              lastChunk && blockSize == chunkSize);
      outputStream.write(compressed, 0, compressedSize);
      uncompressedOffset += blockSize;
      chunkSize -= blockSize;
    } while (chunkSize > 0);

    if (lastChunk) {
      int hash = (int) partialHash.hash();
      outputStream.write(hash);
      outputStream.write(hash >> 8);
      outputStream.write(hash >> 16);
      outputStream.write(hash >> 24);
    } else {
      int slideWindowSize = uncompressedOffset - context.parameters.getWindowSize();
      context.slideWindow(slideWindowSize);

      System.arraycopy(
          uncompressed,
          slideWindowSize,
          uncompressed,
          0,
          context.parameters.getWindowSize() + (uncompressedPosition - uncompressedOffset));
      uncompressedOffset -= slideWindowSize;
      uncompressedPosition -= slideWindowSize;
    }
  }
}

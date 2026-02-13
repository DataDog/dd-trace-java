package com.datadog.profiling.utils.zstd;

import static com.datadog.profiling.utils.zstd.CompressionParameters.DEFAULT_COMPRESSION_LEVEL;
import static com.datadog.profiling.utils.zstd.Util.checkState;
import static com.datadog.profiling.utils.zstd.ZstdConstants.SIZE_OF_BLOCK_HEADER;
import static com.datadog.profiling.utils.zstd.ZstdConstants.SIZE_OF_LONG;
import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.util.Objects.requireNonNull;

import datadog.trace.util.UnsafeUtils;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;

/** Streaming zstd compressor. Buffers input and compresses in blocks. */
public class ZstdOutputStream extends OutputStream {
  private final OutputStream outputStream;
  private final CompressionContext context;
  private final int maxBufferSize;

  private XxHash64 partialHash;

  private byte[] uncompressed = new byte[0];
  private final byte[] compressed;

  private int uncompressedOffset;
  private int uncompressedPosition;

  private boolean closed;

  public ZstdOutputStream(OutputStream outputStream) throws IOException {
    this.outputStream = requireNonNull(outputStream, "outputStream is null");
    this.context =
        new CompressionContext(
            CompressionParameters.compute(DEFAULT_COMPRESSION_LEVEL, -1),
            UnsafeUtils.BYTE_ARRAY_BASE_OFFSET,
            Integer.MAX_VALUE);
    this.maxBufferSize = context.parameters.getWindowSize() * 4;

    int bufferSize = context.parameters.getBlockSize() + SIZE_OF_BLOCK_HEADER;
    this.compressed = new byte[bufferSize + (bufferSize >>> 8) + SIZE_OF_LONG];
  }

  @Override
  public void write(int b) throws IOException {
    if (closed) {
      throw new IOException("Stream is closed");
    }

    growBufferIfNecessary(1);
    uncompressed[uncompressedPosition++] = (byte) b;
    compressIfNecessary();
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
    if (offset < 0 || length < 0 || offset + length > buffer.length) {
      throw new IndexOutOfBoundsException(
          "offset=" + offset + ", length=" + length + ", buffer.length=" + buffer.length);
    }
    if (closed) {
      throw new IOException("Stream is closed");
    }

    growBufferIfNecessary(length);

    while (length > 0) {
      int writeSize = min(length, uncompressed.length - uncompressedPosition);
      System.arraycopy(buffer, offset, uncompressed, uncompressedPosition, writeSize);

      uncompressedPosition += writeSize;
      length -= writeSize;
      offset += writeSize;

      compressIfNecessary();
    }
  }

  private void growBufferIfNecessary(int length) {
    if (uncompressedPosition + length <= uncompressed.length
        || uncompressed.length >= maxBufferSize) {
      return;
    }

    int newSize = (uncompressed.length + length) * 2;
    newSize = min(newSize, maxBufferSize);
    newSize = max(newSize, context.parameters.getBlockSize());
    uncompressed = Arrays.copyOf(uncompressed, newSize);
  }

  private void compressIfNecessary() throws IOException {
    if (uncompressed.length >= maxBufferSize
        && uncompressedPosition == uncompressed.length
        && uncompressed.length - context.parameters.getWindowSize()
            > context.parameters.getBlockSize()) {
      writeChunk(false);
    }
  }

  @Override
  public void flush() throws IOException {
    if (!closed && uncompressedPosition > uncompressedOffset) {
      writeChunk(false);
    }
    outputStream.flush();
  }

  @Override
  public void close() throws IOException {
    if (!closed) {
      writeChunk(true);
      closed = true;
      outputStream.close();
    }
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

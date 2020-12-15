package com.datadog.profiling.uploader.util;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import net.jpountz.lz4.LZ4FrameOutputStream;
import org.openjdk.jmc.common.io.IOToolkit;

/** A collection of I/O stream related helper methods */
public final class StreamUtils {

  // https://github.com/lz4/lz4/blob/dev/doc/lz4_Frame_format.md#general-structure-of-lz4-frame-format
  static final int[] LZ4_MAGIC = new int[] {0x04, 0x22, 0x4D, 0x18};

  // JMC's IOToolkit hides this from us...
  static final int ZIP_MAGIC[] = new int[] {80, 75, 3, 4};
  static final int GZ_MAGIC[] = new int[] {31, 139};

  private static final byte[] COPY_BUFFER = new byte[8192];
  private static final FastByteArrayOutputStream COMPRESSION_BUFFER = new FastByteArrayOutputStream(8 * 1024 * 1024); // 8MB should be sufficient for typical profiles

  /**
   * Consumes array or bytes along with offset and length and turns it into something usable.
   *
   * <p>Main idea here is that we may end up having array with valuable data siting somehere in the
   * middle and we can avoid additional copies by allowing user to deal with this directly and
   * convert it into whatever format it needs in most efficient way.
   *
   * @param <T> result type
   */
  @FunctionalInterface
  public interface BytesConsumer<T> {
    T consume(byte[] bytes, int offset, int length);
  }

  /**
   * Read a stream into a consumer gzip-compressing content. If the stream is already compressed
   * (gzip, zip, lz4) the original data will be returned.
   *
   * @param is the input stream
   * @return gzipped contents of the input stream or the the original content if the stream is
   *     already compressed
   * @throws IOException
   */
  public static <T> T gzipStream(
      InputStream is, final BytesConsumer<T> consumer) throws IOException {
    is = ensureMarkSupported(is);
    if (isCompressed(is)) {
      return readStream(is, consumer);
    } else {
      synchronized (COMPRESSION_BUFFER) {
        try {
          try (final OutputStream zipped = new GZIPOutputStream(COMPRESSION_BUFFER)) {
            copy(is, zipped);
          }
          return COMPRESSION_BUFFER.consume(consumer);
        } finally {
          COMPRESSION_BUFFER.reset();
        }
      }
    }
  }

  /**
   * Read a stream into a consumer lz4-compressing content. If the stream is already compressed
   * (gzip, zip, lz4) the original data will be returned.
   *
   * @param is the input stream
   * @return lz4ed contents of the input stream or the the original content if the stream is already
   *     compressed
   * @throws IOException
   */
  public static <T> T lz4Stream(
      InputStream is, final BytesConsumer<T> consumer) throws IOException {
    is = ensureMarkSupported(is);
    if (isCompressed(is)) {
      return readStream(is, consumer);
    } else {
      synchronized (COMPRESSION_BUFFER) {
        try {
          try (final OutputStream zipped =
                 new LZ4FrameOutputStream(
                   COMPRESSION_BUFFER,
                   LZ4FrameOutputStream.BLOCKSIZE.SIZE_64KB,
                   // copy of the default flag(s) used by LZ4FrameOutputStream
                   LZ4FrameOutputStream.FLG.Bits.BLOCK_INDEPENDENCE)) {
            copy(is, zipped);
          }
          return COMPRESSION_BUFFER.consume(consumer);
        } finally {
          COMPRESSION_BUFFER.reset();
        }
      }
    }
  }

  /**
   * Read a stream into a consumer.
   *
   * <p>Note: the idea here comes from Guava's {@link com.google.common.io.ByteStreams}, but we
   * cannot use that directly because it is not public and is not flexible enough.
   *
   * @param is the input stream
   * @param consumer consumer to convert byte array to result
   * @return the stream data
   * @throws IOException
   */
  public static <T> T readStream(
      final InputStream is, final BytesConsumer<T> consumer)
      throws IOException {
    synchronized (COPY_BUFFER) {
      int expectedSize = COPY_BUFFER.length;
      int remaining = expectedSize;

      while (remaining > 0) {
        final int offset = expectedSize - remaining;
        final int read = is.read(COPY_BUFFER, offset, remaining);
        if (read == -1) {
          // end of stream before reading expectedSize bytes just return the bytes read so far
          // 'offset' here is offset in 'bytes' buffer - which essentially represents length of data
          // read so far.
          return consumer.consume(COPY_BUFFER, 0, offset);
        }
        remaining -= read;
      }

      // the stream was longer, so read the rest manually
      final List<BufferChunk> additionalChunks = new ArrayList<>();
      int additionalChunksLength = 0;

      while (true) {
        final BufferChunk chunk = new BufferChunk(Math.max(32, is.available()));
        final int readLength = chunk.readFrom(is);
        if (readLength < 0) {
          break;
        } else {
          additionalChunks.add(chunk);
          additionalChunksLength += readLength;
        }
      }

      // now assemble resulting array
      final byte[] result = new byte[COPY_BUFFER.length + additionalChunksLength];
      System.arraycopy(COPY_BUFFER, 0, result, 0, COPY_BUFFER.length);
      int offset = COPY_BUFFER.length;
      for (final BufferChunk chunk : additionalChunks) {
        offset += chunk.appendToArray(result, offset);
      }
      return consumer.consume(result, 0, result.length);
    }
  }

  private static class BufferChunk {

    private int size = 0;
    private final byte[] buf;

    public BufferChunk(final int initialSize) {
      buf = new byte[initialSize];
    }

    public int readFrom(final InputStream is) throws IOException {
      size = is.read(buf, 0, buf.length);
      return size;
    }

    public int appendToArray(final byte[] array, final int offset) {
      System.arraycopy(buf, 0, array, offset, size);
      return size;
    }
  }

  // Helper ByteArrayOutputStream that avoids some data copies
  private static final class FastByteArrayOutputStream extends ByteArrayOutputStream {

    public FastByteArrayOutputStream(final int size) {
      super(size);
    }

    /**
     * ByteArrayOutputStream's API doesn't allow us to get data without a copy. We add this method
     * to support this.
     */
    <T> T consume(final BytesConsumer<T> consumer) {
      return consumer.consume(buf, 0, count);
    }
  }

  /**
   * Copy an input stream into an output stream
   *
   * @param is input
   * @param os output
   * @throws IOException
   */
  private static void copy(final InputStream is, final OutputStream os) throws IOException {
    int length;
    synchronized (COPY_BUFFER) {
      while ((length = is.read(COPY_BUFFER)) > 0) {
        os.write(COPY_BUFFER, 0, length);
      }
    }
  }

  /**
   * Check whether the stream is compressed using a supported format
   *
   * @param is input stream; must support {@linkplain InputStream#mark(int)}
   * @return {@literal true} if the stream is compressed in a supported format
   * @throws IOException
   */
  private static boolean isCompressed(final InputStream is) throws IOException {
    checkMarkSupported(is);
    return isGzip(is) || isLz4(is) || isZip(is);
  }

  /**
   * Check whether the stream represents GZip data
   *
   * @param is input stream; must support {@linkplain InputStream#mark(int)}
   * @return {@literal true} if the stream represents GZip data
   * @throws IOException
   */
  private static boolean isGzip(final InputStream is) throws IOException {
    checkMarkSupported(is);
    is.mark(GZ_MAGIC.length);
    try {
      return IOToolkit.hasMagic(is, GZ_MAGIC);
    } finally {
      is.reset();
    }
  }

  /**
   * Check whether the stream represents Zip data
   *
   * @param is input stream; must support {@linkplain InputStream#mark(int)}
   * @return {@literal true} if the stream represents Zip data
   * @throws IOException
   */
  private static boolean isZip(final InputStream is) throws IOException {
    checkMarkSupported(is);
    is.mark(ZIP_MAGIC.length);
    try {
      return IOToolkit.hasMagic(is, ZIP_MAGIC);
    } finally {
      is.reset();
    }
  }

  /**
   * Check whether the stream represents LZ4 data
   *
   * @param is input stream; must support {@linkplain InputStream#mark(int)}
   * @return {@literal true} if the stream represents LZ4 data
   * @throws IOException
   */
  private static boolean isLz4(final InputStream is) throws IOException {
    checkMarkSupported(is);
    is.mark(LZ4_MAGIC.length);
    try {
      return IOToolkit.hasMagic(is, LZ4_MAGIC);
    } finally {
      is.reset();
    }
  }

  private static InputStream ensureMarkSupported(InputStream is) {
    if (!is.markSupported()) {
      is = new BufferedInputStream(is);
    }
    return is;
  }

  private static void checkMarkSupported(final InputStream is) throws IOException {
    if (!is.markSupported()) {
      throw new IOException("Can not check headers on streams not supporting mark() method");
    }
  }
}

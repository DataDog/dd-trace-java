package com.datadog.profiling.uploader;

import datadog.trace.api.Platform;
import datadog.trace.api.profiling.RecordingInputStream;
import io.airlift.compress.zstd.ZstdOutputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.GZIPOutputStream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FrameOutputStream;
import net.jpountz.xxhash.XXHashFactory;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okio.BufferedSink;
import okio.Okio;
import okio.Source;

/**
 * A specialized {@linkplain RequestBody} subclass performing on-the fly compression of the uploaded
 * data.
 */
final class CompressingRequestBody extends RequestBody {

  /*
   * LZ4 is not available in native image.
   * LZ4Factory is using reflection heavily and since we are shading the lz4 classes for the usage in profiler
   * it seems to be impossible to configure the native-image generation such as to make the reflection work.
   *
   * For now, we are disabling the lz4 compression in native image.
   */
  private static final LZ4Factory LZ4_FACTORY =
      Platform.isNativeImage() ? null : LZ4Factory.fastestJavaInstance();
  private static final XXHashFactory XXHASH_FACTORY =
      Platform.isNativeImage() ? null : XXHashFactory.fastestJavaInstance();

  static final class MissingInputException extends IOException {
    public MissingInputException(String message) {
      super(message);
    }
  }

  /** A simple functional supplier throwing an {@linkplain IOException} */
  @FunctionalInterface
  interface InputStreamSupplier {
    RecordingInputStream get() throws IOException;
  }

  /** A simple functional mapper allowing to throw {@linkplain IOException} */
  @FunctionalInterface
  interface OutputStreamMappingFunction {
    OutputStream apply(OutputStream param) throws IOException;
  }

  /**
   * A data upload retry policy. By using this policy it is possible to customize how many times the
   * data upload will be reattempted if the input data stream is unavailable.
   */
  @FunctionalInterface
  interface RetryPolicy {
    /**
     * @param ordinal number of data upload attempts so far
     * @return {@literal true} if the data upload should be retried
     */
    boolean shouldRetry(int ordinal);
  }

  /**
   * A data upload retry backoff policy. This policy will be used to obtain the delay before the
   * next retry.
   */
  @FunctionalInterface
  interface RetryBackoff {
    /**
     * @param ordinal number of data upload attempts so far
     * @return the required delay in milliscenods before next retry
     */
    int backoff(int ordinal);
  }

  static final MediaType OCTET_STREAM = MediaType.parse("application/octet-stream");

  // https://github.com/lz4/lz4/blob/dev/doc/lz4_Frame_format.md#general-structure-of-lz4-frame-format
  private static final int[] LZ4_MAGIC = new int[] {0x04, 0x22, 0x4D, 0x18};
  private static final int[] ZIP_MAGIC = new int[] {80, 75, 3, 4};
  private static final int[] GZ_MAGIC = new int[] {31, 139};
  private static final int[] ZSTD_MAGIC = new int[] {0x28, 0xB5, 0x2F, 0xFD};

  private final InputStreamSupplier inputStreamSupplier;
  private final OutputStreamMappingFunction outputStreamMapper;
  private final RetryPolicy retryPolicy;
  private final RetryBackoff retryBackoff;

  private long readBytes = 0;
  private long writtenBytes = 0;

  /**
   * Create a new instance configured with 1 retry and constant 10ms backoff delay.
   *
   * @param compressionType {@linkplain CompressionType} value
   * @param inputStreamSupplier supplier of the data input stream
   */
  CompressingRequestBody(
      @Nonnull CompressionType compressionType, @Nonnull InputStreamSupplier inputStreamSupplier) {
    this(compressionType, inputStreamSupplier, r -> r <= 1, r -> 10);
  }

  /**
   * Create a new instance configured with constant 10ms backoff delay.
   *
   * @param compressionType {@linkplain CompressionType} value
   * @param inputStreamSupplier supplier of the data input stream
   * @param retryPolicy {@linkplain RetryPolicy} instance
   */
  CompressingRequestBody(
      @Nonnull CompressionType compressionType,
      @Nonnull InputStreamSupplier inputStreamSupplier,
      @Nonnull RetryPolicy retryPolicy) {
    this(compressionType, inputStreamSupplier, retryPolicy, r -> 10);
  }

  /**
   * Create a new instance.
   *
   * @param compressionType {@linkplain CompressionType} value
   * @param inputStreamSupplier supplier of the data input stream
   * @param retryPolicy {@linkplain RetryPolicy} instance
   * @param retryBackoff {@linkplain RetryBackoff} instance
   */
  CompressingRequestBody(
      @Nonnull CompressionType compressionType,
      @Nonnull InputStreamSupplier inputStreamSupplier,
      @Nonnull RetryPolicy retryPolicy,
      @Nonnull RetryBackoff retryBackoff) {
    this.inputStreamSupplier = inputStreamSupplier;
    this.outputStreamMapper = getOutputStreamMapper(compressionType);
    this.retryPolicy = retryPolicy;
    this.retryBackoff = retryBackoff;
  }

  @Override
  public long contentLength() throws IOException {
    // uploading chunked streaming data -> the length is unknown
    return -1;
  }

  @Nullable
  @Override
  public MediaType contentType() {
    return OCTET_STREAM;
  }

  @Override
  public void writeTo(BufferedSink bufferedSink) throws IOException {
    Throwable lastException = null;
    boolean shouldRetry = false;
    int retry = 1;
    do {
      /*
       * Here we do attempt a simple retry functionality in case the input stream can not be obtained -
       * which is usually caused by the JFR recording being not finalized on disk in our case.
       * The number of times this should be re-attempted as well as the backoff between the attempts
       * can be defined per CompressingRequestBody instance.
       *
       * However, the failures in reading the input stream and writing the data out to the Okio sink
       * will not be retried because doing so can result in corrupted data uploads. Instead, the client
       * should use the OkHttpClient callback to get notified about failed requests and handle the retries
       * at the request level.
       */
      try (RecordingInputStream recordingInputStream = inputStreamSupplier.get()) {
        if (recordingInputStream.isEmpty()) {
          // The recording stream appears to be empty.
          // Simply fail the request - there is 0% chance that it suddenly becomes non-empty.
          throw new MissingInputException("Empty recording");
        }
        ByteCountingInputStream inputStream = new ByteCountingInputStream(recordingInputStream);
        // Got the input stream so clear the 'lastException'
        lastException = null;
        try {
          ByteCountingOutputStream outputStream =
              new ByteCountingOutputStream(bufferedSink.outputStream());
          attemptWrite(inputStream, outputStream);
          readBytes = inputStream.getReadBytes();
          writtenBytes = outputStream.getWrittenBytes();
        } catch (Throwable t) {
          // Only the failures while obtaining the input stream are retriable.
          // Any failure during reading that input stream must make this write to fail as well.
          lastException = t;
          shouldRetry = false;
        }
      } catch (MissingInputException e) {
        // The recording is empty - just re-throw
        throw e;
      } catch (Throwable t) {
        // Only the failures while obtaining the input stream are retriable.
        lastException = t;
        shouldRetry = true;
      }
      if (shouldRetry && retryPolicy.shouldRetry(retry)) {
        long backoffMs = retryBackoff.backoff(retry);
        try {
          Thread.sleep(backoffMs);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IOException(e);
        }
        retry++;
      } else {
        shouldRetry = false;
      }
    } while (shouldRetry);
    if (lastException != null) {
      throw lastException instanceof IOException
          ? (IOException) lastException
          : new IOException(lastException);
    }
  }

  long getReadBytes() {
    return readBytes;
  }

  long getWrittenBytes() {
    return writtenBytes;
  }

  private void attemptWrite(@Nonnull InputStream inputStream, @Nonnull OutputStream outputStream)
      throws IOException {
    try (OutputStream sinkStream =
        isCompressed(inputStream)
            ? new BufferedOutputStream(outputStream) {
              @Override
              public void close() throws IOException {
                // Do not propagate close; call 'flush()' instead.
                // Compression streams must be 'closed' because they finalize the
                // compression
                // in that method.
                flush();
              }
            }
            : new BufferedOutputStream(
                outputStreamMapper.apply(
                    new BufferedOutputStream(outputStream) {
                      @Override
                      public void close() throws IOException {
                        // Do not propagate close; call 'flush()' instead.
                        // Compression streams must be 'closed' because they finalize the
                        // compression in that method.
                        flush();
                      }
                    }))) {
      BufferedSink sink = Okio.buffer(Okio.sink(sinkStream));
      try (Source source = Okio.buffer(Okio.source(inputStream))) {
        sink.writeAll(source);
      }
      // a bit of cargo-culting to make sure that all writes have really-really been flushed
      sink.emit();
      sink.flush();
    }
  }

  /**
   * Check whether the stream is compressed using a supported format
   *
   * @param is input stream; must support {@linkplain InputStream#mark(int)}
   * @return {@literal true} if the stream is compressed in a supported format
   * @throws IOException
   */
  static boolean isCompressed(@Nonnull final InputStream is) throws IOException {
    checkMarkSupported(is);
    return isGzip(is) || isLz4(is) || isZip(is) || isZstd(is);
  }

  /**
   * Check whether the stream represents ZSTD data
   *
   * @param is input stream; must support {@linkplain InputStream#mark(int)}
   * @return {@literal true} if the stream represents ZSTD data
   * @throws IOException
   */
  static boolean isZstd(@Nonnull final InputStream is) throws IOException {
    checkMarkSupported(is);
    is.mark(ZSTD_MAGIC.length);
    try {
      return hasMagic(is, ZSTD_MAGIC);
    } finally {
      is.reset();
    }
  }

  /**
   * Check whether the stream represents GZip data
   *
   * @param is input stream; must support {@linkplain InputStream#mark(int)}
   * @return {@literal true} if the stream represents GZip data
   * @throws IOException
   */
  static boolean isGzip(@Nonnull final InputStream is) throws IOException {
    checkMarkSupported(is);
    is.mark(GZ_MAGIC.length);
    try {
      return hasMagic(is, GZ_MAGIC);
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
  private static boolean isZip(@Nonnull final InputStream is) throws IOException {
    checkMarkSupported(is);
    is.mark(ZIP_MAGIC.length);
    try {
      return hasMagic(is, ZIP_MAGIC);
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
  static boolean isLz4(@Nonnull final InputStream is) throws IOException {
    checkMarkSupported(is);
    is.mark(LZ4_MAGIC.length);
    try {
      return hasMagic(is, LZ4_MAGIC);
    } finally {
      is.reset();
    }
  }

  private static void checkMarkSupported(@Nonnull final InputStream is) throws IOException {
    if (!is.markSupported()) {
      throw new IOException("Can not check headers on streams not supporting mark() method");
    }
  }

  private static OutputStreamMappingFunction getOutputStreamMapper(
      @Nonnull CompressionType compressionType) {
    // Handle native image compatibility
    if (Platform.isNativeImage() && compressionType != CompressionType.OFF) {
      compressionType = CompressionType.GZIP;
    }

    switch (compressionType) {
      case GZIP:
        {
          return GZIPOutputStream::new;
        }
      case OFF:
        {
          return out -> out;
        }
      case LZ4:
        {
          return CompressingRequestBody::toLz4Stream;
        }
      case ON:
      case ZSTD:
      default:
        {
          return CompressingRequestBody::toZstdStream;
        }
    }
  }

  private static OutputStream toZstdStream(@Nonnull OutputStream os) throws IOException {
    // Default compression level is 3 which provides a good balance between performance and
    // compression ratio
    return new ZstdOutputStream(os);
  }

  private static OutputStream toLz4Stream(@Nonnull OutputStream os) throws IOException {
    return new LZ4FrameOutputStream(
        os,
        LZ4FrameOutputStream.BLOCKSIZE.SIZE_64KB,
        -1L,
        LZ4_FACTORY.fastCompressor(),
        XXHASH_FACTORY.hash32(),
        // copy of the default flag(s) used by LZ4FrameOutputStream
        LZ4FrameOutputStream.FLG.Bits.BLOCK_INDEPENDENCE);
  }

  private static boolean hasMagic(InputStream is, int[] magic) throws IOException {
    for (int element : magic) {
      int b = is.read();
      if (b != element) {
        return false;
      }
    }
    return true;
  }
}

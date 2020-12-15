package com.datadog.profiling.uploader;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.GZIPOutputStream;
import javax.annotation.Nullable;
import net.jpountz.lz4.LZ4FrameOutputStream;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okio.BufferedSink;
import okio.Okio;
import okio.Source;
import org.openjdk.jmc.common.io.IOToolkit;

final class CompressingRequestBody extends RequestBody {
  @FunctionalInterface
  interface RetryPolicy {
    boolean shouldRetry(int ordinal);
  }

  @FunctionalInterface
  interface RetryBackoff {
    int backoff(int ordinal);
  }

  static final MediaType OCTET_STREAM = MediaType.parse("application/octet-stream");

  // https://github.com/lz4/lz4/blob/dev/doc/lz4_Frame_format.md#general-structure-of-lz4-frame-format
  private static final int[] LZ4_MAGIC = new int[] {0x04, 0x22, 0x4D, 0x18};

  // JMC's IOToolkit hides this from us...
  private static final int ZIP_MAGIC[] = new int[] {80, 75, 3, 4};
  private static final int GZ_MAGIC[] = new int[] {31, 139};

  private final MediaType mediaType;
  private final InputStreamSupplier inputStreamSupplier;
  private final OutputStreamMappingFunction outputStreamMapper;
  private final RetryPolicy retryPolicy;
  private final RetryBackoff retryBackoff;

  CompressingRequestBody(CompressionType compressionType, InputStreamSupplier inputStreamSupplier) {
    this(compressionType, inputStreamSupplier, r -> r <= 1, r -> 10);
  }

  CompressingRequestBody(
      CompressionType compressionType,
      InputStreamSupplier inputStreamSupplier,
      RetryPolicy retryPolicy) {
    this(compressionType, inputStreamSupplier, retryPolicy, r -> 10);
  }

  CompressingRequestBody(
      CompressionType compressionType,
      InputStreamSupplier inputStreamSupplier,
      RetryPolicy retryPolicy,
      RetryBackoff retryBackoff) {
    this.mediaType = OCTET_STREAM;
    this.inputStreamSupplier = () -> ensureMarkSupported(inputStreamSupplier.get());
    this.outputStreamMapper = getOutputStreamMapper(compressionType);
    this.retryPolicy = retryPolicy;
    this.retryBackoff = retryBackoff;
  }

  @Override
  public long contentLength() throws IOException {
    return -1;
  }

  @Nullable
  @Override
  public MediaType contentType() {
    return mediaType;
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
      try (InputStream inputStream = inputStreamSupplier.get()) {
        // Got the input stream so clear the 'lastException'
        lastException = null;
        try {
          attemptWrite(inputStream, bufferedSink);
        } catch (Throwable t) {
          // Only the failures while obtaining the input stream are retriable.
          // Any failure during reading that input stream must make this write to fail as well.
          lastException = t;
          shouldRetry = false;
        }
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

  private void attemptWrite(InputStream inputStream, BufferedSink bufferedSink) throws IOException {
    if (!isCompressed(inputStream)) {
      OutputStream outputStream =
          new BufferedOutputStream(
              outputStreamMapper.apply(
                  new BufferedOutputStream(bufferedSink.outputStream()) {
                    @Override
                    public void close() throws IOException {
                      // Do not propagate close; call 'flush()' instead.
                      // Compression streams must be 'closed' because they finalize the compression
                      // in that method.
                      flush();
                    }
                  }));
      BufferedSink sink = Okio.buffer(Okio.sink(outputStream));
      try (Source source = Okio.buffer(Okio.source(inputStream))) {
        sink.writeAll(source);
      }
      // a bit of cargo-culting to make sure that all writes have really-really been flushed
      sink.emit();
      sink.flush();
      outputStream.close();
    } else {
      bufferedSink.writeAll(Okio.buffer(Okio.source(inputStream)));
    }
  }

  private static InputStream ensureMarkSupported(InputStream is) {
    if (!is.markSupported()) {
      is = new BufferedInputStream(is);
    }
    return is;
  }

  /**
   * Check whether the stream is compressed using a supported format
   *
   * @param is input stream; must support {@linkplain InputStream#mark(int)}
   * @return {@literal true} if the stream is compressed in a supported format
   * @throws IOException
   */
  static boolean isCompressed(final InputStream is) throws IOException {
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
  static boolean isGzip(final InputStream is) throws IOException {
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
  static boolean isZip(final InputStream is) throws IOException {
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
  static boolean isLz4(final InputStream is) throws IOException {
    checkMarkSupported(is);
    is.mark(LZ4_MAGIC.length);
    try {
      return IOToolkit.hasMagic(is, LZ4_MAGIC);
    } finally {
      is.reset();
    }
  }

  private static void checkMarkSupported(final InputStream is) throws IOException {
    if (!is.markSupported()) {
      throw new IOException("Can not check headers on streams not supporting mark() method");
    }
  }

  private static OutputStreamMappingFunction getOutputStreamMapper(
      CompressionType compressionType) {
    // currently only gzip and off are supported
    // this needs to be updated once more compression types are added
    switch (compressionType) {
      case GZIP:
        {
          return GZIPOutputStream::new;
        }
      case OFF:
        {
          return out -> out;
        }
      case ON:
      case LZ4:
      default:
        {
          return CompressingRequestBody::toLz4Stream;
        }
    }
  }

  private static OutputStream toLz4Stream(OutputStream os) throws IOException {
    return new LZ4FrameOutputStream(
        os,
        LZ4FrameOutputStream.BLOCKSIZE.SIZE_64KB,
        // copy of the default flag(s) used by LZ4FrameOutputStream
        LZ4FrameOutputStream.FLG.Bits.BLOCK_INDEPENDENCE);
  }
}

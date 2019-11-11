package com.datadog.profiling.uploader.util;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipInputStream;

/** A collection of I/O stream related helper methods */
public final class StreamUtils {
  /**
   * Unpack a stream with supported compression (zip, gzip). If the stream is not compressed or the
   * compression is not recognized it will return the original content.
   *
   * @param compressed the input data
   * @return unpacked contents of the input stream or the original ones if not compresses or
   *     unrecognized compression
   * @throws IOException
   */
  public static byte[] unpack(byte[] compressed) throws IOException {
    InputStream is = new ByteArrayInputStream(compressed);
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    try (OutputStream os = baos) {
      if (isZip(is)) {
        is = new ZipInputStream(is);
      } else if (isGzip(is)) {
        is = new GZIPInputStream(is);
      }
      copy(is, os);
    }
    return baos.toByteArray();
  }

  /**
   * Zip compress the given input stream. If the stream is already zip-compressed (gzip, zip) the
   * original data will be returned.
   *
   * @param is the input stream
   * @return zipped contents of the input stream or the the original content if the stream is
   *     already compressed
   * @throws IOException
   */
  public static byte[] zipStream(InputStream is) throws IOException {
    is = ensureMarkSupported(is);
    ByteArrayOutputStream baos = new ByteArrayOutputStream();

    // if the input stream is already compressed just pass-through to the ByteArrayOutputStream
    // instance
    try (OutputStream zipped = isCompressed(is) ? baos : new GZIPOutputStream(baos)) {
      copy(is, zipped);
    }
    return baos.toByteArray();
  }

  /**
   * Read a stream as byte array
   *
   * @param is the input stream
   * @return the stream data
   * @throws IOException
   */
  public static byte[] readStream(InputStream is) throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    try (ByteArrayOutputStream out = baos) {
      copy(is, out);
    }
    return baos.toByteArray();
  }

  /**
   * Copy an input stream into an output stream
   *
   * @param is input
   * @param os output
   * @throws IOException
   */
  public static void copy(final InputStream is, final OutputStream os) throws IOException {
    int length;
    final byte[] buffer = new byte[8192];
    while ((length = is.read(buffer)) > 0) {
      os.write(buffer, 0, length);
    }
    os.flush();
  }

  /**
   * Check whether the stream is compressed using a supported format
   *
   * @param is input stream; must support {@linkplain InputStream#mark(int)}
   * @return {@literal true} if the stream is compressed in a supported format
   * @throws IOException
   */
  public static boolean isCompressed(InputStream is) throws IOException {
    checkMarkSupported(is);
    return isGzip(is) || isZip(is);
  }

  /**
   * Check whether the stream represents GZip data
   *
   * @param is input stream; must support {@linkplain InputStream#mark(int)}
   * @return {@literal true} if the stream represents GZip data
   * @throws IOException
   */
  public static boolean isGzip(InputStream is) throws IOException {
    checkMarkSupported(is);
    is.mark(IOToolkit.GZ_MAGIC.length);
    try {
      return IOToolkit.hasMagic(is, IOToolkit.GZ_MAGIC);
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
  public static boolean isZip(InputStream is) throws IOException {
    checkMarkSupported(is);
    is.mark(IOToolkit.ZIP_MAGIC.length);
    try {
      return IOToolkit.hasMagic(is, IOToolkit.ZIP_MAGIC);
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

  private static void checkMarkSupported(InputStream is) throws IOException {
    if (!is.markSupported()) {
      throw new IOException("Can not check headers on streams not supporting mark() method");
    }
  }
}

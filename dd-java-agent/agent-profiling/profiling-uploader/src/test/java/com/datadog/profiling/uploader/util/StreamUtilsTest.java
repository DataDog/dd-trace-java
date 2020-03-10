package com.datadog.profiling.uploader.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import com.google.common.io.ByteStreams;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import net.jpountz.lz4.LZ4FrameInputStream;
import net.jpountz.lz4.LZ4FrameOutputStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class StreamUtilsTest {

  private final int DEFAULT_EXPECTED_SIZE = 100;
  private final StreamUtils.BytesConsumer<byte[]> CONSUME_TO_BYTES =
      (bytes, offset, length) -> Arrays.copyOfRange(bytes, offset, offset + length);

  private static byte[] testRecordingBytes;
  private static byte[] testRecordingGzippedBytes;
  private static byte[] testRecordingZippedBytes;
  private static byte[] testRecordingLz4edBytes;

  @BeforeAll
  public static void setupClass() throws IOException {
    testRecordingBytes = ByteStreams.toByteArray(testRecordingStream());

    final ByteArrayOutputStream gzippedStream = new ByteArrayOutputStream();
    ByteStreams.copy(testRecordingStream(), new GZIPOutputStream(gzippedStream));
    testRecordingGzippedBytes = gzippedStream.toByteArray();

    final ByteArrayOutputStream zippedStream = new ByteArrayOutputStream();
    ByteStreams.copy(testRecordingStream(), createZipOutputStream(zippedStream));
    testRecordingZippedBytes = zippedStream.toByteArray();

    final ByteArrayOutputStream zl4edStream = new ByteArrayOutputStream();
    ByteStreams.copy(testRecordingStream(), new LZ4FrameOutputStream(zl4edStream));
    testRecordingLz4edBytes = zl4edStream.toByteArray();
  }

  @Test
  public void readStreamNoCompression() throws IOException {
    final int expectedSize = 1; // Try very small value to test 'undershoot' logic
    final byte[] bytes =
        StreamUtils.readStream(testRecordingStream(), expectedSize, CONSUME_TO_BYTES);
    assertArrayEquals(testRecordingBytes, bytes);
  }

  @Test
  public void readStreamNoCompressionLargeExpectedSize() throws IOException {
    final int expectedSize = testRecordingBytes.length * 2; // overshoot the size
    final byte[] bytes =
        StreamUtils.readStream(testRecordingStream(), expectedSize, CONSUME_TO_BYTES);
    assertArrayEquals(testRecordingBytes, bytes);
  }

  @Test
  public void gzipStream() throws IOException {
    final int expectedSize = 1; // Try very small value to test 'undershoot' logic
    final byte[] gzippedBytes =
        StreamUtils.gzipStream(testRecordingStream(), expectedSize, CONSUME_TO_BYTES);

    assertArrayEquals(testRecordingBytes, uncompressGzip(gzippedBytes));
  }

  @Test
  public void gzipStreamLargeExpectedSize() throws IOException {
    final int expectedSize = testRecordingBytes.length * 2; // overshoot the size
    final byte[] gzippedBytes =
        StreamUtils.gzipStream(testRecordingStream(), expectedSize, CONSUME_TO_BYTES);

    assertArrayEquals(testRecordingBytes, uncompressGzip(gzippedBytes));
  }

  @Test
  public void gzipAlreadyGzippedStream() throws IOException {
    final byte[] bytes =
        StreamUtils.gzipStream(
            new ByteArrayInputStream(testRecordingGzippedBytes),
            DEFAULT_EXPECTED_SIZE,
            CONSUME_TO_BYTES);

    assertArrayEquals(testRecordingGzippedBytes, bytes);
  }

  @Test
  public void gzipAlreadyZippedStream() throws IOException {
    final byte[] bytes =
        StreamUtils.gzipStream(
            new ByteArrayInputStream(testRecordingZippedBytes),
            DEFAULT_EXPECTED_SIZE,
            CONSUME_TO_BYTES);

    assertArrayEquals(testRecordingZippedBytes, bytes);
  }

  @Test
  public void gzipAlreadyLz4edStream() throws IOException {
    final byte[] bytes =
        StreamUtils.gzipStream(
            new ByteArrayInputStream(testRecordingLz4edBytes),
            DEFAULT_EXPECTED_SIZE,
            CONSUME_TO_BYTES);

    assertArrayEquals(testRecordingLz4edBytes, bytes);
  }

  @Test
  public void lz4Stream() throws IOException {
    final int expectedSize = 1; // Try very small value to test 'undershoot' logic
    final byte[] gzippedBytes =
        StreamUtils.lz4Stream(testRecordingStream(), expectedSize, CONSUME_TO_BYTES);

    assertArrayEquals(testRecordingBytes, uncompressLz4(gzippedBytes));
  }

  @Test
  public void lz4StreamLargeExpectedSize() throws IOException {
    final int expectedSize = testRecordingBytes.length * 2; // overshoot the size
    final byte[] gzippedBytes =
        StreamUtils.lz4Stream(testRecordingStream(), expectedSize, CONSUME_TO_BYTES);

    assertArrayEquals(testRecordingBytes, uncompressLz4(gzippedBytes));
  }

  @Test
  public void lz4AlreadyGzippedStream() throws IOException {
    final byte[] bytes =
        StreamUtils.lz4Stream(
            new ByteArrayInputStream(testRecordingGzippedBytes),
            DEFAULT_EXPECTED_SIZE,
            CONSUME_TO_BYTES);

    assertArrayEquals(testRecordingGzippedBytes, bytes);
  }

  @Test
  public void lz4AlreadyZippedStream() throws IOException {
    final byte[] bytes =
        StreamUtils.lz4Stream(
            new ByteArrayInputStream(testRecordingZippedBytes),
            DEFAULT_EXPECTED_SIZE,
            CONSUME_TO_BYTES);

    assertArrayEquals(testRecordingZippedBytes, bytes);
  }

  @Test
  public void lz4AlreadyLz4edStream() throws IOException {
    final byte[] bytes =
        StreamUtils.lz4Stream(
            new ByteArrayInputStream(testRecordingLz4edBytes),
            DEFAULT_EXPECTED_SIZE,
            CONSUME_TO_BYTES);

    assertArrayEquals(testRecordingLz4edBytes, bytes);
  }

  private static InputStream testRecordingStream() {
    return StreamUtilsTest.class.getResourceAsStream("/test-recording.jfr");
  }

  private static ZipOutputStream createZipOutputStream(final OutputStream stream)
      throws IOException {
    final ZipOutputStream result = new ZipOutputStream(stream);
    result.putNextEntry(new ZipEntry("test"));
    return result;
  }

  private static byte[] uncompressGzip(final byte[] bytes) throws IOException {
    return ByteStreams.toByteArray(new GZIPInputStream(new ByteArrayInputStream(bytes)));
  }

  private static byte[] uncompressLz4(final byte[] bytes) throws IOException {
    return ByteStreams.toByteArray(new LZ4FrameInputStream(new ByteArrayInputStream(bytes)));
  }
}

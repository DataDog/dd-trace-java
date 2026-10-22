package com.datadog.featureflag;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.GZIPOutputStream;
import okhttp3.MediaType;
import okhttp3.ResponseBody;
import okio.BufferedSource;
import okio.Okio;
import org.junit.jupiter.api.Test;

class UfcResponseBodyReaderTest {

  @Test
  void acceptsIdentityResponseAtLimit() throws Exception {
    final byte[] body =
        UfcResponseBodyReader.read(
            responseBody(
                new RepeatingInputStream(UfcResponseBodyReader.MAX_DECOMPRESSED_BYTES),
                UfcResponseBodyReader.MAX_DECOMPRESSED_BYTES),
            null);

    assertEquals(UfcResponseBodyReader.MAX_DECOMPRESSED_BYTES, body.length);
  }

  @Test
  void acceptsIdentityContentEncoding() throws Exception {
    final byte[] expected = "identity UFC".getBytes("UTF-8");

    final byte[] actual =
        UfcResponseBodyReader.read(
            responseBody(new ByteArrayInputStream(expected), expected.length), "identity");

    assertArrayEquals(expected, actual);
  }

  @Test
  void acceptsKnownEmptyIdentityResponseWithoutReading() throws Exception {
    assertArrayEquals(new byte[0], UfcResponseBodyReader.read(unreadableResponseBody(0), null));
  }

  @Test
  void acceptsChunkedEmptyIdentityResponse() throws Exception {
    assertArrayEquals(
        new byte[0],
        UfcResponseBodyReader.read(responseBody(new ByteArrayInputStream(new byte[0]), -1), null));
  }

  @Test
  void rejectsKnownIdentityLengthBeforeReading() {
    final ResponseBody body =
        unreadableResponseBody(UfcResponseBodyReader.MAX_DECOMPRESSED_BYTES + 1L);

    final UfcResponseBodyReader.ResponseTooLargeException failure =
        assertThrows(
            UfcResponseBodyReader.ResponseTooLargeException.class,
            () -> UfcResponseBodyReader.read(body, null));

    assertEquals(UfcResponseBodyReader.MAX_DECOMPRESSED_BYTES, failure.limitBytes);
  }

  @Test
  void rejectsChunkedIdentityResponseAboveLimit() {
    final ResponseBody body =
        responseBody(
            new RepeatingInputStream(UfcResponseBodyReader.MAX_DECOMPRESSED_BYTES + 1L), -1);

    assertThrows(
        UfcResponseBodyReader.ResponseTooLargeException.class,
        () -> UfcResponseBodyReader.read(body, null));
  }

  @Test
  void rejectsBodyThatExceedsMisleadingContentLength() {
    final ResponseBody body =
        responseBody(
            new RepeatingInputStream(UfcResponseBodyReader.MAX_DECOMPRESSED_BYTES + 1L), 1);

    assertThrows(
        UfcResponseBodyReader.ResponseTooLargeException.class,
        () -> UfcResponseBodyReader.read(body, null));
  }

  @Test
  void rejectsKnownCompressedLengthBeforeReading() {
    final ResponseBody body =
        unreadableResponseBody(UfcResponseBodyReader.MAX_COMPRESSED_BYTES + 1L);

    final UfcResponseBodyReader.ResponseTooLargeException failure =
        assertThrows(
            UfcResponseBodyReader.ResponseTooLargeException.class,
            () -> UfcResponseBodyReader.read(body, "gzip"));

    assertEquals(UfcResponseBodyReader.MAX_COMPRESSED_BYTES, failure.limitBytes);
  }

  @Test
  void rejectsChunkedCompressedResponseAboveLimit() throws Exception {
    final byte[] compressed = gzipRandom(UfcResponseBodyReader.MAX_DECOMPRESSED_BYTES);
    assertTrue(compressed.length > UfcResponseBodyReader.MAX_COMPRESSED_BYTES);

    final UfcResponseBodyReader.ResponseTooLargeException failure =
        assertThrows(
            UfcResponseBodyReader.ResponseTooLargeException.class,
            () ->
                UfcResponseBodyReader.read(
                    responseBody(new ByteArrayInputStream(compressed), -1), "gzip"));

    assertTrue(failure.getMessage().contains("compressed"));
  }

  @Test
  void rejectsGzipExpansionAboveLimit() throws Exception {
    final byte[] compressed = gzipRepeated(UfcResponseBodyReader.MAX_DECOMPRESSED_BYTES + 1L);

    assertThrows(
        UfcResponseBodyReader.ResponseTooLargeException.class,
        () ->
            UfcResponseBodyReader.read(
                responseBody(new ByteArrayInputStream(compressed), compressed.length), "gzip"));
  }

  @Test
  void decodesGzipWithinLimits() throws Exception {
    final byte[] expected = "bounded UFC".getBytes("UTF-8");
    final byte[] compressed = gzip(expected);

    final byte[] actual =
        UfcResponseBodyReader.read(
            responseBody(new ByteArrayInputStream(compressed), compressed.length), " GZIP ");

    assertArrayEquals(expected, actual);
  }

  @Test
  void limitsSingleByteReads() throws Exception {
    final InputStream input =
        new UfcResponseBodyReader.LimitedInputStream(
            new ByteArrayInputStream(new byte[] {1, 2}), 1, "compressed");

    assertEquals(1, input.read());
    assertThrows(UfcResponseBodyReader.ResponseTooLargeException.class, input::read);
  }

  @Test
  void allowsEndOfInputAfterBulkReads() throws Exception {
    final InputStream input =
        new UfcResponseBodyReader.LimitedInputStream(
            new ByteArrayInputStream(new byte[0]), 1, "compressed");

    assertEquals(-1, input.read(new byte[1]));
  }

  private static ResponseBody responseBody(final InputStream input, final long contentLength) {
    final BufferedSource source = Okio.buffer(Okio.source(input));
    return new ResponseBody() {
      @Override
      public MediaType contentType() {
        return MediaType.get("application/json");
      }

      @Override
      public long contentLength() {
        return contentLength;
      }

      @Override
      public BufferedSource source() {
        return source;
      }
    };
  }

  private static ResponseBody unreadableResponseBody(final long contentLength) {
    return new ResponseBody() {
      @Override
      public MediaType contentType() {
        return MediaType.get("application/json");
      }

      @Override
      public long contentLength() {
        return contentLength;
      }

      @Override
      public BufferedSource source() {
        throw new AssertionError("response body must not be read");
      }
    };
  }

  private static byte[] gzip(final byte[] value) throws IOException {
    final ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
      gzip.write(value);
    }
    return output.toByteArray();
  }

  private static byte[] gzipRepeated(final long size) throws IOException {
    final ByteArrayOutputStream output = new ByteArrayOutputStream();
    final byte[] chunk = new byte[8 << 10];
    Arrays.fill(chunk, (byte) 'x');
    long remaining = size;
    try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
      while (remaining > 0) {
        final int count = (int) Math.min(chunk.length, remaining);
        gzip.write(chunk, 0, count);
        remaining -= count;
      }
    }
    return output.toByteArray();
  }

  private static byte[] gzipRandom(final long size) throws IOException {
    final ByteArrayOutputStream output = new ByteArrayOutputStream();
    final byte[] chunk = new byte[8 << 10];
    final Random random = new Random(123456789L);
    long remaining = size;
    try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
      while (remaining > 0) {
        random.nextBytes(chunk);
        final int count = (int) Math.min(chunk.length, remaining);
        gzip.write(chunk, 0, count);
        remaining -= count;
      }
    }
    return output.toByteArray();
  }

  private static final class RepeatingInputStream extends InputStream {
    private long remaining;

    private RepeatingInputStream(final long remaining) {
      this.remaining = remaining;
    }

    @Override
    public int read() {
      if (remaining == 0) {
        return -1;
      }
      remaining--;
      return 'x';
    }

    @Override
    public int read(final byte[] bytes, final int offset, final int length) {
      if (remaining == 0) {
        return -1;
      }
      final int count = (int) Math.min(length, remaining);
      Arrays.fill(bytes, offset, offset + count, (byte) 'x');
      remaining -= count;
      return count;
    }
  }
}

package datadog.openfeature.internal.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

class UfcResponseBodyReaderTest {

  @Test
  void returnsIdentityBodyWithoutAnotherCopy() throws Exception {
    final byte[] body = "identity UFC".getBytes(StandardCharsets.UTF_8);

    assertSame(body, UfcResponseBodyReader.decode(body, "identity"));
    assertSame(body, UfcResponseBodyReader.decode(body, null));
    assertEquals(0, UfcResponseBodyReader.decode(new byte[0], null).length);
  }

  @Test
  void rejectsIdentityBodyAboveTheLimit() {
    final byte[] body = new byte[UfcResponseBodyReader.MAX_DECOMPRESSED_BYTES + 1];

    final UfcResponseBodyReader.ResponseTooLargeException error =
        assertThrows(
            UfcResponseBodyReader.ResponseTooLargeException.class,
            () -> UfcResponseBodyReader.decode(body, null));

    assertEquals(UfcResponseBodyReader.MAX_DECOMPRESSED_BYTES, error.limitBytes);
  }

  @Test
  void decodesGzipWithinTheLimit() throws Exception {
    final byte[] expected = "bounded UFC".getBytes(StandardCharsets.UTF_8);

    assertArrayEquals(expected, UfcResponseBodyReader.decode(gzip(expected), " GZIP "));
  }

  @Test
  void rejectsGzipExpansionAboveTheLimit() throws Exception {
    final byte[] compressed = gzipRepeated(UfcResponseBodyReader.MAX_DECOMPRESSED_BYTES + 1L);

    assertThrows(
        UfcResponseBodyReader.ResponseTooLargeException.class,
        () -> UfcResponseBodyReader.decode(compressed, "gzip"));
  }

  @Test
  void limitsSingleAndBulkCompressedReads() throws Exception {
    final InputStream single =
        new UfcResponseBodyReader.LimitedInputStream(
            new ByteArrayInputStream(new byte[] {1, 2}), 1, "compressed");
    assertEquals(1, single.read());
    assertThrows(UfcResponseBodyReader.ResponseTooLargeException.class, single::read);

    final InputStream bulk =
        new UfcResponseBodyReader.LimitedInputStream(
            new ByteArrayInputStream(new byte[] {1, 2}), 1, "compressed");
    assertThrows(
        UfcResponseBodyReader.ResponseTooLargeException.class, () -> bulk.read(new byte[2]));
  }

  @Test
  void allowsEndOfInputAfterBulkRead() throws Exception {
    final InputStream input =
        new UfcResponseBodyReader.LimitedInputStream(
            new ByteArrayInputStream(new byte[0]), 1, "compressed");

    assertEquals(-1, input.read(new byte[1]));
  }

  private static byte[] gzip(final byte[] input) throws IOException {
    final ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
      gzip.write(input);
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
}

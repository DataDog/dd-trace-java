package datadog.trace.core.otlp.common;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.zip.GZIPInputStream;
import okhttp3.MediaType;
import okio.Buffer;
import org.junit.jupiter.api.Test;

class OtlpHttpRequestBodyTest {

  @Test
  void contentTypeIsParsedFromPayload() {
    OtlpHttpRequestBody body =
        new OtlpHttpRequestBody(payload("application/x-protobuf", new byte[] {1, 2, 3}), false);

    assertEquals(MediaType.get("application/x-protobuf"), body.contentType());
  }

  @Test
  void contentLengthMatchesPayloadWhenUncompressed() {
    OtlpHttpRequestBody body =
        new OtlpHttpRequestBody(payload("application/x-protobuf", new byte[] {1, 2, 3, 4}), false);

    assertEquals(4, body.contentLength());
  }

  @Test
  void contentLengthIsNegativeOneWhenGzipped() {
    // gzip writes chunked, so the framework can't know the length up front
    OtlpHttpRequestBody body =
        new OtlpHttpRequestBody(payload("application/x-protobuf", new byte[] {1, 2, 3, 4}), true);

    assertEquals(-1, body.contentLength());
  }

  @Test
  void writeToDrainsRawBytesWhenUncompressed() throws IOException {
    byte[] data = {10, 20, 30, 40, 50};
    OtlpHttpRequestBody body =
        new OtlpHttpRequestBody(payload("application/x-protobuf", data), false);
    Buffer sink = new Buffer();

    body.writeTo(sink);

    assertArrayEquals(data, sink.readByteArray());
  }

  @Test
  void writeToConcatenatesMultipleChunksWhenUncompressed() throws IOException {
    OtlpHttpRequestBody body =
        new OtlpHttpRequestBody(
            payload(
                "application/x-protobuf", new byte[] {1, 2}, new byte[] {3, 4, 5}, new byte[] {6}),
            false);
    Buffer sink = new Buffer();

    body.writeTo(sink);

    assertArrayEquals(new byte[] {1, 2, 3, 4, 5, 6}, sink.readByteArray());
  }

  @Test
  void writeToProducesGzipStreamThatDecompressesToPayloadWhenGzipped() throws IOException {
    byte[] data = "the quick brown fox jumps over the lazy dog".getBytes();
    OtlpHttpRequestBody body =
        new OtlpHttpRequestBody(payload("application/x-protobuf", data), true);
    Buffer sink = new Buffer();

    body.writeTo(sink);

    assertArrayEquals(data, gunzip(sink.readByteArray()));
  }

  private static OtlpPayload payload(String contentType, byte[]... chunks) {
    Deque<byte[]> deque = new ArrayDeque<>();
    int total = 0;
    for (byte[] chunk : chunks) {
      deque.add(chunk);
      total += chunk.length;
    }
    return new OtlpPayload(deque, total, contentType);
  }

  private static byte[] gunzip(byte[] gzipped) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (InputStream gz = new GZIPInputStream(new ByteArrayInputStream(gzipped))) {
      byte[] buf = new byte[256];
      int n;
      while ((n = gz.read(buf)) > 0) {
        out.write(buf, 0, n);
      }
    }
    return out.toByteArray();
  }
}

package datadog.trace.core.otlp.common;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.zip.GZIPInputStream;
import okhttp3.MediaType;
import okio.Buffer;
import org.junit.jupiter.api.Test;

class OtlpGrpcRequestBodyTest {

  @Test
  void contentTypeIsApplicationGrpc() {
    OtlpGrpcRequestBody body =
        new OtlpGrpcRequestBody(
            new OtlpPayload(ByteBuffer.wrap(new byte[] {1, 2, 3}), "application/grpc+proto"),
            false);

    assertEquals(MediaType.get("application/grpc"), body.contentType());
  }

  @Test
  void contentLengthIsHeaderPlusPayloadWhenUncompressed() {
    OtlpGrpcRequestBody body =
        new OtlpGrpcRequestBody(
            new OtlpPayload(ByteBuffer.wrap(new byte[] {1, 2, 3, 4}), "application/grpc+proto"),
            false);

    // 5-byte header (1 flag + 4 length) + 4-byte payload = 9
    assertEquals(9, body.contentLength());
  }

  @Test
  void contentLengthIsNegativeOneWhenGzipped() {
    OtlpGrpcRequestBody body =
        new OtlpGrpcRequestBody(
            new OtlpPayload(ByteBuffer.wrap(new byte[] {1, 2, 3, 4}), "application/grpc+proto"),
            true);

    assertEquals(-1, body.contentLength());
  }

  @Test
  void writeToProducesGrpcFrameWithUncompressedFlagWhenNotGzipped() throws IOException {
    byte[] data = {10, 20, 30, 40, 50};
    OtlpGrpcRequestBody body =
        new OtlpGrpcRequestBody(
            new OtlpPayload(ByteBuffer.wrap(data), "application/grpc+proto"), false);
    Buffer sink = new Buffer();

    body.writeTo(sink);

    assertEquals(0, sink.readByte()); // uncompressed flag
    assertEquals(data.length, sink.readInt()); // 4-byte big-endian length
    assertArrayEquals(data, sink.readByteArray()); // payload
  }

  @Test
  void writeToProducesGrpcFrameWithCompressedFlagAndGzipDataWhenGzipped() throws IOException {
    byte[] data = "the quick brown fox jumps over the lazy dog".getBytes();
    OtlpGrpcRequestBody body =
        new OtlpGrpcRequestBody(
            new OtlpPayload(ByteBuffer.wrap(data), "application/grpc+proto"), true);
    Buffer sink = new Buffer();

    body.writeTo(sink);

    assertEquals(1, sink.readByte()); // compressed flag
    int gzipLength = sink.readInt(); // 4-byte big-endian gzip content length
    byte[] gzipped = sink.readByteArray(gzipLength);
    assertArrayEquals(data, gunzip(gzipped));
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

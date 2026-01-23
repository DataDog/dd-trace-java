package datadog.communication.http.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class HttpRequestBodyTest {

  @Test
  void testOfString() throws IOException {
    String content = "{\"key\":\"value\"}";
    HttpRequestBody body = HttpRequestBody.of(content);

    assertNotNull(body);
    assertEquals(content.length(), body.contentLength());

    byte[] written = writeToBytes(body);
    assertEquals(content, new String(written));
  }

  @Test
  void testOfStringWithUnicode() throws IOException {
    String content = "{\"message\":\"Hello 世界\"}";
    HttpRequestBody body = HttpRequestBody.of(content);

    assertNotNull(body);
    byte[] written = writeToBytes(body);
    assertEquals(content, new String(written, "UTF-8"));
  }

  @Test
  void testOfNullString() {
    assertThrows(NullPointerException.class, () -> {
      HttpRequestBody.of(null);
    });
  }

  @Test
  void testMsgpack() throws IOException {
    ByteBuffer buffer1 = ByteBuffer.wrap(new byte[]{1, 2, 3});
    ByteBuffer buffer2 = ByteBuffer.wrap(new byte[]{4, 5, 6});
    List<ByteBuffer> buffers = Arrays.asList(buffer1, buffer2);

    HttpRequestBody body = HttpRequestBody.msgpack(buffers);

    assertNotNull(body);
    assertEquals(6, body.contentLength());

    byte[] written = writeToBytes(body);
    assertEquals(6, written.length);
    assertEquals(1, written[0]);
    assertEquals(6, written[5]);
  }

  @Test
  void testMsgpackEmpty() throws IOException {
    List<ByteBuffer> buffers = Arrays.asList();
    HttpRequestBody body = HttpRequestBody.msgpack(buffers);

    assertNotNull(body);
    assertEquals(0, body.contentLength());
  }

  @Test
  void testMsgpackNull() {
    assertThrows(NullPointerException.class, () -> {
      HttpRequestBody.msgpack(null);
    });
  }

  @Test
  void testGzip() throws IOException {
    String content = "test data to compress";
    HttpRequestBody original = HttpRequestBody.of(content);
    HttpRequestBody compressed = HttpRequestBody.gzip(original);

    assertNotNull(compressed);
    // Gzipped content length is unknown (-1)
    assertEquals(-1, compressed.contentLength());

    byte[] written = writeToBytes(compressed);
    // Gzipped data should be smaller for this content
    assertTrue(written.length > 0);
    // Verify gzip magic number
    assertEquals((byte) 0x1f, written[0]);
    assertEquals((byte) 0x8b, written[1]);
  }

  @Test
  void testGzipNull() {
    assertThrows(NullPointerException.class, () -> {
      HttpRequestBody.gzip(null);
    });
  }

  @Test
  void testMultipart() {
    HttpRequestBody.MultipartBuilder builder = HttpRequestBody.multipart();

    assertNotNull(builder);
  }

  @Test
  void testMultipartWithFormData() throws IOException {
    HttpRequestBody.MultipartBuilder builder = HttpRequestBody.multipart();
    builder.addFormDataPart("field1", "value1");
    builder.addFormDataPart("field2", "value2");

    HttpRequestBody body = builder.build();
    assertNotNull(body);

    byte[] written = writeToBytes(body);
    String content = new String(written);

    assertTrue(content.contains("field1"));
    assertTrue(content.contains("value1"));
    assertTrue(content.contains("field2"));
    assertTrue(content.contains("value2"));
  }

  @Test
  void testMultipartWithFile() throws IOException {
    HttpRequestBody.MultipartBuilder builder = HttpRequestBody.multipart();
    HttpRequestBody fileBody = HttpRequestBody.of("file content");

    builder.addFormDataPart("file_field", "filename.txt", fileBody);

    HttpRequestBody body = builder.build();
    assertNotNull(body);

    byte[] written = writeToBytes(body);
    String content = new String(written);

    assertTrue(content.contains("file_field"));
    assertTrue(content.contains("filename.txt"));
    assertTrue(content.contains("file content"));
  }

  private byte[] writeToBytes(HttpRequestBody body) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    body.writeTo(out);
    return out.toByteArray();
  }
}

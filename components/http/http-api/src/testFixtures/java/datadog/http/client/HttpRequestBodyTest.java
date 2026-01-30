package datadog.http.client;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class HttpRequestBodyTest {

  // TODO Test empty string
  // TODO Test empty byte array
  // TODO Test empty ByteBuffer list

  @Test
  void testNullString() {
    assertThrows(NullPointerException.class, () -> HttpRequestBody.of((String) null));
  }

  @Test
  void testNullBytes() {
    assertThrows(NullPointerException.class, () -> HttpRequestBody.of((byte[]) null));
  }

  @Test
  void testNullByteBuffer() {
    assertThrows(NullPointerException.class, () -> HttpRequestBody.of((List<ByteBuffer>) null));
  }

  @Test
  void testMultipartBuilder() {
    HttpRequestBody.MultipartBuilder builder = HttpRequestBody.multipart();
    assertNotNull(builder);
  }

  @Test
  void testMultipartContentType() {
    HttpRequestBody.MultipartBuilder builder = HttpRequestBody.multipart();
    builder.addFormDataPart("name", "value");
    String contentType = builder.contentType();
    assertTrue(contentType.startsWith("multipart/form-data; boundary="));
  }

  @Test
  void testMultipartAddFormDataPart() throws IOException {
    HttpRequestBody.MultipartBuilder builder = HttpRequestBody.multipart();
    builder.addFormDataPart("name", "value");
    HttpRequestBody body = builder.build();

    assertNotNull(body);
    assertTrue(body.contentLength() > 0);

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    body.writeTo(out);
    String content = out.toString("UTF-8");
    assertTrue(content.contains("name=\"name\""));
    assertTrue(content.contains("value"));
  }

  @Test
  void testMultipartAddFormDataPartWithFile() throws IOException {
    HttpRequestBody.MultipartBuilder builder = HttpRequestBody.multipart();
    HttpRequestBody fileBody = HttpRequestBody.of("file content");
    builder.addFormDataPart("file", "test.txt", fileBody);
    HttpRequestBody body = builder.build();

    assertNotNull(body);
    assertTrue(body.contentLength() > 0);

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    body.writeTo(out);
    String content = out.toString("UTF-8");
    assertTrue(content.contains("name=\"file\""));
    assertTrue(content.contains("filename=\"test.txt\""));
    assertTrue(content.contains("file content"));
  }

  @Test
  void testMultipartAddPart() throws IOException {
    HttpRequestBody.MultipartBuilder builder = HttpRequestBody.multipart();
    Map<String, String> headers = new HashMap<>();
    headers.put("Content-Disposition", "form-data; name=\"custom\"; filename=\"data.bin\"");
    HttpRequestBody partBody = HttpRequestBody.of("custom content");
    builder.addPart(headers, partBody);
    HttpRequestBody body = builder.build();

    assertNotNull(body);
    assertTrue(body.contentLength() > 0);

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    body.writeTo(out);
    String content = out.toString("UTF-8");
    assertTrue(content.contains("name=\"custom\""));
    assertTrue(content.contains("custom content"));
  }

  @Test
  void testMultipartNullParams() {
    HttpRequestBody.MultipartBuilder builder = HttpRequestBody.multipart();
    assertThrows(NullPointerException.class, () -> builder.addFormDataPart(null, "value"));
    assertThrows(NullPointerException.class, () -> builder.addFormDataPart("name", null));

    HttpRequestBody fileBody = HttpRequestBody.of("content");
    assertThrows(
        NullPointerException.class, () -> builder.addFormDataPart(null, "file.txt", fileBody));
    assertThrows(
        NullPointerException.class, () -> builder.addFormDataPart("name", null, fileBody));
    assertThrows(
        NullPointerException.class, () -> builder.addFormDataPart("name", "file.txt", null));

    HttpRequestBody partBody = HttpRequestBody.of("content");
    Map<String, String> headers = new HashMap<>();
    headers.put("Content-Disposition", "form-data; name=\"test\"");
    assertThrows(NullPointerException.class, () -> builder.addPart(null, partBody));
    assertThrows(NullPointerException.class, () -> builder.addPart(headers, null));
  }
}

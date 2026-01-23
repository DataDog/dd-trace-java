package datadog.communication.http.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;

class HttpResponseTest {

  @Test
  void testSuccessfulResponse() throws IOException {
    okhttp3.Response okHttpResponse = new okhttp3.Response.Builder()
        .request(new Request.Builder().url("http://localhost:8080/test").build())
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .body(ResponseBody.create(MediaType.get("text/plain"), "response body"))
        .build();

    HttpResponse response = HttpResponse.wrap(okHttpResponse);

    assertNotNull(response);
    assertEquals(200, response.code());
    assertTrue(response.isSuccessful());

    InputStream body = response.body();
    assertNotNull(body);
    String content = readAll(body);
    assertEquals("response body", content);

    response.close();
  }

  @Test
  void testErrorResponse() {
    okhttp3.Response okHttpResponse = new okhttp3.Response.Builder()
        .request(new Request.Builder().url("http://localhost:8080/test").build())
        .protocol(Protocol.HTTP_1_1)
        .code(404)
        .message("Not Found")
        .body(ResponseBody.create(MediaType.get("text/plain"), ""))
        .build();

    HttpResponse response = HttpResponse.wrap(okHttpResponse);

    assertNotNull(response);
    assertEquals(404, response.code());
    assertFalse(response.isSuccessful());

    response.close();
  }

  @Test
  void testServerErrorResponse() {
    okhttp3.Response okHttpResponse = new okhttp3.Response.Builder()
        .request(new Request.Builder().url("http://localhost:8080/test").build())
        .protocol(Protocol.HTTP_1_1)
        .code(500)
        .message("Internal Server Error")
        .body(ResponseBody.create(MediaType.get("text/plain"), ""))
        .build();

    HttpResponse response = HttpResponse.wrap(okHttpResponse);

    assertEquals(500, response.code());
    assertFalse(response.isSuccessful());

    response.close();
  }

  @Test
  void testSingleHeader() {
    okhttp3.Response okHttpResponse = new okhttp3.Response.Builder()
        .request(new Request.Builder().url("http://localhost:8080/test").build())
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .header("Content-Type", "application/json")
        .body(ResponseBody.create(MediaType.get("text/plain"), ""))
        .build();

    HttpResponse response = HttpResponse.wrap(okHttpResponse);

    assertEquals("application/json", response.header("Content-Type"));
    assertEquals("application/json", response.header("content-type")); // case-insensitive
    assertNull(response.header("X-Missing-Header"));

    response.close();
  }

  @Test
  void testMultipleHeaders() {
    okhttp3.Response okHttpResponse = new okhttp3.Response.Builder()
        .request(new Request.Builder().url("http://localhost:8080/test").build())
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .addHeader("Set-Cookie", "session=abc123")
        .addHeader("Set-Cookie", "user=john")
        .addHeader("Cache-Control", "no-cache")
        .body(ResponseBody.create(MediaType.get("text/plain"), ""))
        .build();

    HttpResponse response = HttpResponse.wrap(okHttpResponse);

    // Single value header
    assertEquals("no-cache", response.header("Cache-Control"));

    // Multi-value header - returns last value per OkHttp behavior
    assertEquals("user=john", response.header("Set-Cookie"));

    // Get all values
    List<String> cookies = response.headers("Set-Cookie");
    assertNotNull(cookies);
    assertEquals(2, cookies.size());
    assertEquals("session=abc123", cookies.get(0));
    assertEquals("user=john", cookies.get(1));

    response.close();
  }

  @Test
  void testEmptyHeadersList() {
    okhttp3.Response okHttpResponse = new okhttp3.Response.Builder()
        .request(new Request.Builder().url("http://localhost:8080/test").build())
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .body(ResponseBody.create(MediaType.get("text/plain"), ""))
        .build();

    HttpResponse response = HttpResponse.wrap(okHttpResponse);

    List<String> missing = response.headers("X-Missing-Header");
    assertNotNull(missing);
    assertTrue(missing.isEmpty());

    response.close();
  }

  @Test
  void testJsonResponse() throws IOException {
    String json = "{\"key\":\"value\",\"number\":42}";
    okhttp3.Response okHttpResponse = new okhttp3.Response.Builder()
        .request(new Request.Builder().url("http://localhost:8080/test").build())
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .header("Content-Type", "application/json")
        .body(ResponseBody.create(MediaType.get("application/json"), json))
        .build();

    HttpResponse response = HttpResponse.wrap(okHttpResponse);

    assertEquals(200, response.code());
    assertEquals("application/json", response.header("Content-Type"));

    String content = readAll(response.body());
    assertEquals(json, content);

    response.close();
  }

  @Test
  void testEmptyBody() throws IOException {
    okhttp3.Response okHttpResponse = new okhttp3.Response.Builder()
        .request(new Request.Builder().url("http://localhost:8080/test").build())
        .protocol(Protocol.HTTP_1_1)
        .code(204)
        .message("No Content")
        .body(ResponseBody.create(MediaType.get("text/plain"), ""))
        .build();

    HttpResponse response = HttpResponse.wrap(okHttpResponse);

    assertEquals(204, response.code());

    String content = readAll(response.body());
    assertEquals("", content);

    response.close();
  }

  @Test
  void testWrapNull() {
    HttpResponse response = HttpResponse.wrap(null);
    assertNull(response);
  }

  private String readAll(InputStream in) throws IOException {
    BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
    StringBuilder sb = new StringBuilder();
    String line;
    while ((line = reader.readLine()) != null) {
      if (sb.length() > 0) {
        sb.append('\n');
      }
      sb.append(line);
    }
    return sb.toString();
  }
}

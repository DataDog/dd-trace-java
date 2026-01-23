package datadog.communication.http.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class HttpRequestTest {

  @Test
  void testGetRequest() {
    HttpUrl url = HttpUrl.parse("http://localhost:8080/api/v1/traces");
    HttpRequest request = HttpRequest.newBuilder()
        .url(url)
        .get()
        .build();

    assertNotNull(request);
    assertEquals(url, request.url());
    assertEquals("GET", request.method());
    assertNull(request.body());
  }

  @Test
  void testPostRequest() {
    HttpUrl url = HttpUrl.parse("http://localhost:8080/api/v1/traces");
    HttpRequestBody body = HttpRequestBody.of("{\"test\":true}");

    HttpRequest request = HttpRequest.newBuilder()
        .url(url)
        .post(body)
        .build();

    assertNotNull(request);
    assertEquals(url, request.url());
    assertEquals("POST", request.method());
    assertNotNull(request.body());
  }

  @Test
  void testPutRequest() {
    HttpUrl url = HttpUrl.parse("http://localhost:8080/api/v1/config");
    HttpRequestBody body = HttpRequestBody.of("{\"config\":true}");

    HttpRequest request = HttpRequest.newBuilder()
        .url(url)
        .put(body)
        .build();

    assertEquals("PUT", request.method());
    assertNotNull(request.body());
  }

  @Test
  void testRequestWithUrlString() {
    HttpRequest request = HttpRequest.newBuilder()
        .url("http://localhost:8080/test")
        .get()
        .build();

    assertNotNull(request);
    assertEquals("http://localhost:8080/test", request.url().url());
  }

  @Test
  void testRequestWithSingleHeader() {
    HttpRequest request = HttpRequest.newBuilder()
        .url("http://localhost:8080/test")
        .header("Content-Type", "application/json")
        .get()
        .build();

    assertEquals("application/json", request.header("Content-Type"));
  }

  @Test
  void testRequestWithMultipleHeaders() {
    HttpRequest request = HttpRequest.newBuilder()
        .url("http://localhost:8080/test")
        .header("Content-Type", "application/json")
        .header("Accept", "application/json")
        .addHeader("X-Custom", "value1")
        .addHeader("X-Custom", "value2")
        .get()
        .build();

    assertEquals("application/json", request.header("Content-Type"));
    assertEquals("application/json", request.header("Accept"));

    List<String> customHeaders = request.headers("X-Custom");
    assertNotNull(customHeaders);
    assertEquals(2, customHeaders.size());
    assertTrue(customHeaders.contains("value1"));
    assertTrue(customHeaders.contains("value2"));
  }

  @Test
  void testBuildWithoutUrl() {
    assertThrows(IllegalStateException.class, () -> {
      HttpRequest.newBuilder()
          .get()
          .build();
    });
  }

  @Test
  void testHeaderReplacement() {
    HttpRequest request = HttpRequest.newBuilder()
        .url("http://localhost:8080/test")
        .header("Content-Type", "text/plain")
        .header("Content-Type", "application/json")  // Replaces previous
        .get()
        .build();

    assertEquals("application/json", request.header("Content-Type"));
  }

  @Test
  void testMissingHeader() {
    HttpRequest request = HttpRequest.newBuilder()
        .url("http://localhost:8080/test")
        .get()
        .build();

    assertNull(request.header("X-Missing"));
    List<String> missing = request.headers("X-Missing");
    assertNotNull(missing);
    assertTrue(missing.isEmpty());
  }
}

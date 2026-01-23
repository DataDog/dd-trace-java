package datadog.communication.http.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class HttpUrlTest {

  @Test
  void testParseValidUrl() {
    HttpUrl url = HttpUrl.parse("http://localhost:8080/api/v1/traces");

    assertNotNull(url);
    assertEquals("http", url.scheme());
    assertEquals("localhost", url.host());
    assertEquals(8080, url.port());
    assertEquals("http://localhost:8080/api/v1/traces", url.url());
  }

  @Test
  void testParseHttpsUrl() {
    HttpUrl url = HttpUrl.parse("https://api.datadoghq.com/api/v2/traces");

    assertNotNull(url);
    assertEquals("https", url.scheme());
    assertEquals("api.datadoghq.com", url.host());
    assertEquals(443, url.port()); // default HTTPS port
    assertEquals("https://api.datadoghq.com/api/v2/traces", url.url());
  }

  @Test
  void testParseUrlWithDefaultPort() {
    HttpUrl url = HttpUrl.parse("http://example.com/path");

    assertEquals(80, url.port()); // default HTTP port
  }

  @Test
  void testResolve() {
    HttpUrl base = HttpUrl.parse("http://localhost:8080/api/");
    HttpUrl resolved = base.resolve("v1/traces");

    assertNotNull(resolved);
    assertEquals("http://localhost:8080/api/v1/traces", resolved.url());
  }

  @Test
  void testResolveAbsolutePath() {
    HttpUrl base = HttpUrl.parse("http://localhost:8080/api/");
    HttpUrl resolved = base.resolve("/v1/traces");

    assertNotNull(resolved);
    assertEquals("http://localhost:8080/v1/traces", resolved.url());
  }

  @Test
  void testBuilder() {
    HttpUrl url = HttpUrl.builder()
        .scheme("https")
        .host("example.com")
        .port(9090)
        .addPathSegment("api")
        .addPathSegment("v1")
        .build();

    assertNotNull(url);
    assertEquals("https", url.scheme());
    assertEquals("example.com", url.host());
    assertEquals(9090, url.port());
    assertEquals("https://example.com:9090/api/v1", url.url());
  }

  @Test
  void testBuilderFromUrl() {
    HttpUrl original = HttpUrl.parse("http://localhost:8080/api/v1");
    HttpUrl modified = original.newBuilder()
        .addPathSegment("traces")
        .build();

    assertEquals("http://localhost:8080/api/v1/traces", modified.url());
  }

  @Test
  void testParseInvalidUrl() {
    assertThrows(IllegalArgumentException.class, () -> {
      HttpUrl.parse("not a valid url");
    });
  }

  @Test
  void testParseNullUrl() {
    assertThrows(NullPointerException.class, () -> {
      HttpUrl.parse(null);
    });
  }
}

package datadog.http.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import org.junit.jupiter.api.Test;

// Mostly a bunch of generated tests to ensure similar behavior of the different implementations
public class HttpUrlTest {

  // ==================== parse() tests ====================

  @Test
  void testParseSimpleUrl() {
    HttpUrl url = HttpUrl.parse("https://example.com");

    assertNotNull(url);
    assertEquals("https", url.scheme());
    assertEquals("example.com", url.host());
    assertEquals(443, url.port());
  }

  @Test
  void testParseUrlWithPort() {
    HttpUrl url = HttpUrl.parse("http://localhost:8080");

    assertEquals("http", url.scheme());
    assertEquals("localhost", url.host());
    assertEquals(8080, url.port());
  }

  @Test
  void testParseUrlWithPath() {
    HttpUrl url = HttpUrl.parse("https://example.com/api/v1/users");

    assertEquals("https", url.scheme());
    assertEquals("example.com", url.host());
    assertTrue(url.url().contains("/api/v1/users"));
  }

  @Test
  void testParseUrlWithQueryParameters() {
    HttpUrl url = HttpUrl.parse("https://example.com/search?q=test&page=1");

    assertTrue(url.url().contains("q=test"));
    assertTrue(url.url().contains("page=1"));
  }

  @Test
  void testParseInvalidUrl() {
    assertThrows(IllegalArgumentException.class, () -> HttpUrl.parse("not a valid url"));
  }

  @Test
  void testParseNullUrl() {
    assertThrows(NullPointerException.class, () -> HttpUrl.parse(null));
  }

  // ==================== from(URI) tests ====================

  @Test
  void testFromUri() {
    URI uri = URI.create("https://example.com:8443/path");
    HttpUrl url = HttpUrl.from(uri);

    assertNotNull(url);
    assertEquals("https", url.scheme());
    assertEquals("example.com", url.host());
    assertEquals(8443, url.port());
    assertTrue(url.url().contains("/path"));
  }

  @Test
  void testFromUriNull() {
    assertThrows(NullPointerException.class, () -> HttpUrl.from(null));
  }

  // ==================== scheme() tests ====================

  @Test
  void testSchemeHttp() {
    HttpUrl url = HttpUrl.parse("http://example.com");
    assertEquals("http", url.scheme());
  }

  @Test
  void testSchemeHttps() {
    HttpUrl url = HttpUrl.parse("https://example.com");
    assertEquals("https", url.scheme());
  }

  // ==================== host() tests ====================

  @Test
  void testHostDomain() {
    HttpUrl url = HttpUrl.parse("https://www.example.com");
    assertEquals("www.example.com", url.host());
  }

  @Test
  void testHostLocalhost() {
    HttpUrl url = HttpUrl.parse("http://localhost:8080");
    assertEquals("localhost", url.host());
  }

  @Test
  void testHostIpAddress() {
    HttpUrl url = HttpUrl.parse("http://192.168.1.1:8080");
    assertEquals("192.168.1.1", url.host());
  }

  // ==================== port() tests ====================

  @Test
  void testPortExplicit() {
    HttpUrl url = HttpUrl.parse("https://example.com:8443");
    assertEquals(8443, url.port());
  }

  @Test
  void testPortDefaultHttp() {
    HttpUrl url = HttpUrl.parse("http://example.com");
    assertEquals(80, url.port());
  }

  @Test
  void testPortDefaultHttps() {
    HttpUrl url = HttpUrl.parse("https://example.com");
    assertEquals(443, url.port());
  }

  // ==================== resolve() tests ====================

  @Test
  void testResolveRelativePath() {
    HttpUrl baseUrl = HttpUrl.parse("https://example.com/api");
    HttpUrl resolved = baseUrl.resolve("users");

    assertTrue(resolved.url().contains("example.com"));
    assertTrue(resolved.url().contains("users"));
  }

  @Test
  void testResolveAbsolutePath() {
    HttpUrl baseUrl = HttpUrl.parse("https://example.com/api/v1");
    HttpUrl resolved = baseUrl.resolve("/v2/users");

    assertTrue(resolved.url().contains("example.com"));
    assertTrue(resolved.url().contains("/v2/users"));
    assertFalse(resolved.url().contains("/api"));
  }

  @Test
  void testResolveWithQueryParameters() {
    HttpUrl baseUrl = HttpUrl.parse("https://example.com/api");
    HttpUrl resolved = baseUrl.resolve("search?q=test");

    assertTrue(resolved.url().contains("search"));
    assertTrue(resolved.url().contains("q=test"));
  }

  // ==================== newBuilder() tests ====================

  @Test
  void testNewBuilderPreservesUrl() {
    HttpUrl original = HttpUrl.parse("https://example.com:8443/api");
    HttpUrl rebuilt = original.newBuilder().build();

    assertEquals(original.scheme(), rebuilt.scheme());
    assertEquals(original.host(), rebuilt.host());
    assertEquals(original.port(), rebuilt.port());
  }

  @Test
  void testNewBuilderAllowsModification() {
    HttpUrl original = HttpUrl.parse("https://example.com/api");
    HttpUrl modified = original.newBuilder()
        .addPathSegment("v2")
        .build();

    assertTrue(modified.url().contains("/api"));
    assertTrue(modified.url().contains("v2"));
  }

  // ==================== addPathSegment() tests ====================

  @Test
  void testAddPathSegmentSingle() {
    HttpUrl url = HttpUrl.builder()
        .scheme("https")
        .host("example.com")
        .addPathSegment("api")
        .build();

    assertTrue(url.url().contains("/api"));
  }

  @Test
  void testAddPathSegmentMultiple() {
    HttpUrl url = HttpUrl.builder()
        .scheme("https")
        .host("example.com")
        .addPathSegment("api")
        .addPathSegment("v1")
        .addPathSegment("users")
        .build();

    String urlString = url.url();
    assertTrue(urlString.contains("/api"));
    assertTrue(urlString.contains("/v1"));
    assertTrue(urlString.contains("/users"));
  }

  @Test
  void testAddPathSegmentWithPort() {
    HttpUrl url = HttpUrl.builder()
        .scheme("https")
        .host("example.com")
        .port(8443)
        .addPathSegment("api")
        .build();

    String urlString = url.url();
    assertTrue(urlString.contains(":8443"));
    assertTrue(urlString.contains("/api"));
  }

  // ==================== builder scheme/host/port tests ====================

  @Test
  void testBuilderSchemeHostPort() {
    HttpUrl url = HttpUrl.builder()
        .scheme("https")
        .host("api.example.com")
        .port(8443)
        .build();

    assertEquals("https", url.scheme());
    assertEquals("api.example.com", url.host());
    assertEquals(8443, url.port());
  }

  @Test
  void testBuilderDefaultScheme() {
    HttpUrl url = HttpUrl.builder()
        .host("example.com")
        .build();

    assertEquals("http", url.scheme());
  }

  // ==================== addQueryParameter() tests ====================

  @Test
  void testAddQueryParameterSingle() {
    HttpUrl url = HttpUrl.builder()
        .scheme("https")
        .host("example.com")
        .addQueryParameter("key", "value")
        .build();

    String urlString = url.url();
    // OkHttp adds trailing slash, JDK doesn't - both are valid
    assertTrue(urlString.matches("https://example\\.com/?\\?key=value"),
        "Expected URL with query parameter, got: " + urlString);
  }

  @Test
  void testAddQueryParameterMultiple() {
    HttpUrl url = HttpUrl.builder()
        .scheme("https")
        .host("example.com")
        .addQueryParameter("key1", "value1")
        .addQueryParameter("key2", "value2")
        .addQueryParameter("key3", "value3")
        .build();

    String urlString = url.url();
    // OkHttp adds trailing slash, JDK doesn't - both are valid
    assertTrue(urlString.matches("https://example\\.com/?\\?.*"),
        "Expected URL with query parameters, got: " + urlString);
    assertTrue(urlString.contains("key1=value1"));
    assertTrue(urlString.contains("key2=value2"));
    assertTrue(urlString.contains("key3=value3"));
    assertTrue(urlString.contains("&"));
  }

  @Test
  void testAddQueryParameterWithNullValue() {
    HttpUrl url = HttpUrl.builder()
        .scheme("https")
        .host("example.com")
        .addQueryParameter("flag", null)
        .build();

    String urlString = url.url();
    assertTrue(urlString.contains("flag"));
    assertFalse(urlString.contains("="));
    assertFalse(urlString.contains("null"));
  }

  @Test
  void testAddQueryParameterWithEncoding() {
    HttpUrl url = HttpUrl.builder()
        .scheme("https")
        .host("example.com")
        .addQueryParameter("message", "hello world")
        .addQueryParameter("special", "a=b&c=d")
        .build();

    String urlString = url.url();
    // Values should be URL encoded - accept both + and %20 for space
    assertTrue(urlString.contains("message=hello+world") || urlString.contains("message=hello%20world"),
        "Expected encoded space in URL, got: " + urlString);
    assertTrue(urlString.contains("special=a%3Db%26c%3Dd"),
        "Expected encoded special chars in URL, got: " + urlString);
  }

  @Test
  void testAddQueryParameterWithPath() {
    HttpUrl url = HttpUrl.builder()
        .scheme("https")
        .host("example.com")
        .addPathSegment("api")
        .addPathSegment("v1")
        .addQueryParameter("page", "1")
        .addQueryParameter("limit", "10")
        .build();

    String urlString = url.url();
    assertTrue(urlString.contains("example.com/api/v1"));
    assertTrue(urlString.contains("page=1"));
    assertTrue(urlString.contains("limit=10"));
  }

  @Test
  void testAddQueryParameterFromExistingUrl() {
    HttpUrl baseUrl = HttpUrl.parse("https://example.com/api");
    HttpUrl url = baseUrl.newBuilder()
        .addQueryParameter("token", "abc123")
        .build();

    String urlString = url.url();
    assertTrue(urlString.contains("example.com/api"));
    assertTrue(urlString.contains("token=abc123"));
  }

  @Test
  void testAddQueryParameterPreservesExistingQuery() {
    HttpUrl baseUrl = HttpUrl.parse("https://example.com/api?existing=param");
    HttpUrl url = baseUrl.newBuilder()
        .addQueryParameter("new", "value")
        .build();

    String urlString = url.url();
    assertTrue(urlString.contains("existing=param"));
    assertTrue(urlString.contains("new=value"));
  }

  @Test
  void testAddQueryParameterEmptyValue() {
    HttpUrl url = HttpUrl.builder()
        .scheme("https")
        .host("example.com")
        .addQueryParameter("key", "")
        .build();

    String urlString = url.url();
    assertTrue(urlString.contains("key="));
  }

  @Test
  void testAddQueryParameterSpecialCharactersInName() {
    HttpUrl url = HttpUrl.builder()
        .scheme("https")
        .host("example.com")
        .addQueryParameter("my-key", "value")
        .addQueryParameter("my_key", "value2")
        .build();

    String urlString = url.url();
    assertTrue(urlString.contains("my-key=value"));
    assertTrue(urlString.contains("my_key=value2"));
  }

  @Test
  void testAddQueryParameterWithPort() {
    HttpUrl url = HttpUrl.builder()
        .scheme("https")
        .host("example.com")
        .port(8443)
        .addQueryParameter("key", "value")
        .build();

    String urlString = url.url();
    assertTrue(urlString.contains(":8443"));
    assertTrue(urlString.contains("key=value"));
  }

  // ==================== url() tests ====================

  @Test
  void testUrlReturnsCompleteUrl() {
    HttpUrl url = HttpUrl.builder()
        .scheme("https")
        .host("example.com")
        .port(8443)
        .addPathSegment("api")
        .addQueryParameter("key", "value")
        .build();

    String urlString = url.url();
    assertTrue(urlString.startsWith("https://"));
    assertTrue(urlString.contains("example.com"));
    assertTrue(urlString.contains(":8443"));
    assertTrue(urlString.contains("/api"));
    assertTrue(urlString.contains("key=value"));
  }

  @Test
  void testUrlMatchesToString() {
    HttpUrl url = HttpUrl.parse("https://example.com/api");
    assertEquals(url.url(), url.toString());
  }
}

package datadog.http.client;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.junit.jupiter.MockServerExtension;

@ExtendWith(MockServerExtension.class)
public class HttpResponseTest {
  private ClientAndServer server;
  private HttpClient client;
  private String baseUrl;

  @BeforeEach
  void setUp(ClientAndServer server) {
    this.server = server;
    this.client = HttpClient.newBuilder()
        .build();
    this.baseUrl = "http://localhost:"+server.getPort();
  }

  @AfterEach
  void tearDown() {
    this.server.reset();
  }

  @Test
  void testBody() throws IOException {
    org.mockserver.model.HttpRequest expectedRequest = request()
        .withMethod("GET")
        .withPath("/test");
    String responseBody = "content";
    this.server.when(expectedRequest).respond(response().withBody(responseBody));

    HttpUrl url = HttpUrl.parse(this.baseUrl + "/test");
    HttpRequest request = HttpRequest.newBuilder()
        .url(url)
        .get()
        .build();

    HttpResponse response = this.client.execute(request);

    assertNotNull(response);
    assertEquals(200, response.code());
    assertTrue(response.isSuccessful());
    try (InputStream body = response.body()) {
      assertEquals(responseBody, readAll(body));
    }

    response.close();
  }

  @Test
  void testEmptyBody() throws IOException {
    org.mockserver.model.HttpRequest expectedRequest = request()
        .withMethod("GET")
        .withPath("/test");
    this.server.when(expectedRequest).respond(response());

    HttpUrl url = HttpUrl.parse(this.baseUrl + "/test");
    HttpRequest request = HttpRequest.newBuilder()
        .url(url)
        .get()
        .build();

    HttpResponse response = this.client.execute(request);

    assertNotNull(response);
    assertEquals(200, response.code());
    assertTrue(response.isSuccessful());
    try (InputStream body = response.body()) {
      assertEquals("", readAll(body));
    }

    response.close();
  }

  @Test
  void testHeader() throws IOException {
    org.mockserver.model.HttpRequest expectedRequest = request()
        .withMethod("GET")
        .withPath("/test");
    org.mockserver.model.HttpResponse resultResponse = response()
        .withHeader("Content-Type", "text/plain");
    this.server.when(expectedRequest).respond(resultResponse);

    HttpUrl url = HttpUrl.parse(this.baseUrl + "/test");
    HttpRequest request = HttpRequest.newBuilder()
        .url(url)
        .get()
        .build();

    HttpResponse response = this.client.execute(request);

    // case-insensitive
    assertEquals("text/plain", response.header("Content-Type"));
    assertEquals("text/plain", response.header("content-type"));
    assertEquals("text/plain", response.header("CONTENT-TYPE"));
    // missing header
    assertNull(response.header("X-Missing-Header"));
    assertTrue(response.headers("X-Missing-Header").isEmpty());

    response.close();
  }

  @Test
  void testHeaderNames() throws IOException {
    org.mockserver.model.HttpRequest expectedRequest = request()
        .withMethod("GET")
        .withPath("/test");
    org.mockserver.model.HttpResponse resultResponse = response()
        .withHeader("Content-Type", "application/json")
        .withHeader("X-Custom-Header", "custom-value")
        .withHeader("X-Another-Header", "another-value");
    this.server.when(expectedRequest).respond(resultResponse);

    HttpUrl url = HttpUrl.parse(this.baseUrl + "/test");
    HttpRequest request = HttpRequest.newBuilder()
        .url(url)
        .get()
        .build();

    HttpResponse response = this.client.execute(request);

    Set<String> headerNames = response.headerNames();
    assertTrue(headerNames.contains("Content-Type"));
    assertTrue(headerNames.contains("X-Custom-Header"));
    assertTrue(headerNames.contains("X-Another-Header"));

    response.close();
  }

  private String readAll(InputStream in) throws IOException {
    BufferedReader reader = new BufferedReader(new InputStreamReader(in, UTF_8));
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

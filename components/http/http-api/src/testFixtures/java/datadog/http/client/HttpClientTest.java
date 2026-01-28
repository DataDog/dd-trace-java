package datadog.http.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.junit.jupiter.MockServerExtension;

@ExtendWith(MockServerExtension.class)
public class HttpClientTest {
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
  void testGetRequest() throws IOException {
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

    this.server.verify(expectedRequest);

    response.close();
  }

  @Test
  void testPostRequest() throws IOException {
    String payload = "{\"key\":\"value\"}";
    org.mockserver.model.HttpRequest expectedRequest = request()
        .withMethod("POST")
        .withPath("/test")
        .withHeader("Content-Type", "application/json")
        .withBody(payload);
    this.server.when(expectedRequest).respond(response().withStatusCode(201));

    HttpUrl url = HttpUrl.parse(this.baseUrl + "/test");
    HttpRequestBody body = HttpRequestBody.of(payload);
    HttpRequest request = HttpRequest.newBuilder()
        .url(url)
        .header("Content-Type", "application/json")
        .post(body)
        .build();

    HttpResponse response = this.client.execute(request);

    assertNotNull(response);
    assertEquals(201, response.code());
    assertTrue(response.isSuccessful());

    this.server.verify(expectedRequest);

    response.close();
  }

  @Test
  void testErrorResponse() throws IOException {
    org.mockserver.model.HttpRequest expectedRequest = request()
        .withMethod("GET")
        .withPath("/missing");
    this.server.when(expectedRequest).respond(response().withStatusCode(404));

    HttpUrl url = HttpUrl.parse(this.baseUrl + "/missing");
    HttpRequest request = HttpRequest.newBuilder()
        .url(url)
        .get()
        .build();

    HttpResponse response = this.client.execute(request);

    assertNotNull(response);
    assertEquals(404, response.code());
    assertFalse(response.isSuccessful());

    this.server.verify(expectedRequest);

    response.close();
  }

  @Test
  void testRequestHeaders() throws IOException {
    org.mockserver.model.HttpRequest expectedRequest = request()
        .withMethod("GET")
        .withPath("/test")
        .withHeader("Accept", "text/plain")
        .withHeader("X-Custom-Header", "custom-value1", "custom-value2", "custom-value3");
    this.server.when(expectedRequest).respond(response().withStatusCode(200));

    HttpUrl url = HttpUrl.parse(this.baseUrl + "/test");
    HttpRequest request = HttpRequest.newBuilder()
        .url(url)
        .get()
        .header("Accept", "text/plain")
        .addHeader("X-Custom-Header", "custom-value1")
        .addHeader("X-Custom-Header", "custom-value2")
        .addHeader("X-Custom-Header", "custom-value3")
        .build();

    HttpResponse response = this.client.execute(request);

    assertNotNull(response);
    assertEquals(200, response.code());
    assertTrue(response.isSuccessful());

    this.server.verify(expectedRequest);

    response.close();
  }

  @Test
  void testResponseHeaders() throws IOException {
    org.mockserver.model.HttpRequest expectedRequest = request()
        .withMethod("GET")
        .withPath("/test");
    org.mockserver.model.HttpResponse resultResponse = response()
        .withStatusCode(200)
        .withHeader("Content-Type", "text/plain")
        .withHeader("X-Custom-Header", "value1", "value2", "value3")
        .withBody("test-response");
    this.server.when(expectedRequest).respond(resultResponse);

    HttpUrl url = HttpUrl.parse(this.baseUrl + "/test");
    HttpRequest request = HttpRequest.newBuilder()
        .url(url)
        .get()
        .build();

    HttpResponse response = this.client.execute(request);

    assertNotNull(response);
    assertEquals(200, response.code());
    assertTrue(response.isSuccessful());
    assertEquals("text/plain", response.header("Content-Type"));
    assertEquals("value1", response.header("X-Custom-Header"));
    List<String> customHeaderValues = response.headers("X-Custom-Header");
    assertEquals(3, customHeaderValues.size());
    assertEquals("value1", customHeaderValues.get(0));
    assertEquals("value2", customHeaderValues.get(1));
    assertEquals("value3", customHeaderValues.get(2));

    this.server.verify(expectedRequest);

    response.close();
  }

  @Test
  void testNewBuilder() {
    HttpClient.Builder builder = HttpClient.newBuilder();
    assertNotNull(builder);

    HttpClient client = builder.build();
    assertNotNull(client);
  }
}

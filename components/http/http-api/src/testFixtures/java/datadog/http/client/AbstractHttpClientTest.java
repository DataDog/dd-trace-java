package datadog.http.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.junit.jupiter.MockServerExtension;

@ExtendWith(MockServerExtension.class)
public abstract class AbstractHttpClientTest {

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

  // @AfterEach
  // void tearDown() throws IOException {
  //   server.shutdown();
  // }

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

  // @Test
  // void testRequestHeaders() throws IOException, InterruptedException {
  //   server.enqueue(new MockResponse()
  //       .setResponseCode(200));
  //
  //   HttpUrl url = HttpUrl.parse(server.url("/test").toString());
  //   HttpRequest request = HttpRequest.newBuilder()
  //       .url(url)
  //       .header("X-Custom-Header", "custom-value")
  //       .header("Accept", "application/json")
  //       .get()
  //       .build();
  //
  //   HttpResponse response = client.execute(request);
  //
  //   assertEquals(200, response.code());
  //
  //   RecordedRequest recordedRequest = server.takeRequest();
  //   assertEquals("custom-value", recordedRequest.getHeader("X-Custom-Header"));
  //   assertEquals("application/json", recordedRequest.getHeader("Accept"));
  //
  //   response.close();
  // }
  //
  // @Test
  // void testResponseHeaders() throws IOException {
  //   server.enqueue(new MockResponse()
  //       .setResponseCode(200)
  //       .addHeader("X-Server-Header", "server-value")
  //       .addHeader("Content-Type", "text/plain")
  //       .setBody("test"));
  //
  //   HttpUrl url = HttpUrl.parse(server.url("/test").toString());
  //   HttpRequest request = HttpRequest.newBuilder()
  //       .url(url)
  //       .get()
  //       .build();
  //
  //   HttpResponse response = client.execute(request);
  //
  //   assertEquals("server-value", response.header("X-Server-Header"));
  //   assertEquals("text/plain", response.header("Content-Type"));
  //
  //   response.close();
  // }

  @Test
  void testNewBuilder() {
    HttpClient.Builder builder = HttpClient.newBuilder();
    assertNotNull(builder);

    HttpClient client = builder.build();
    assertNotNull(client);
  }
}

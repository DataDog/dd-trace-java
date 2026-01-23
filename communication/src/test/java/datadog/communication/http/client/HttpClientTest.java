package datadog.communication.http.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HttpClientTest {

  private MockWebServer server;
  private HttpClient client;

  @BeforeEach
  void setUp() throws IOException {
    server = new MockWebServer();
    server.start();

    client = HttpClient.newBuilder()
        .build();
  }

  @AfterEach
  void tearDown() throws IOException {
    server.shutdown();
  }

  @Test
  void testGetRequest() throws IOException, InterruptedException {
    server.enqueue(new MockResponse()
        .setResponseCode(200)
        .setBody("test response"));

    HttpUrl url = HttpUrl.parse(server.url("/test").toString());
    HttpRequest request = HttpRequest.newBuilder()
        .url(url)
        .get()
        .build();

    HttpResponse response = client.execute(request);

    assertNotNull(response);
    assertEquals(200, response.code());
    assertTrue(response.isSuccessful());

    RecordedRequest recordedRequest = server.takeRequest();
    assertEquals("GET", recordedRequest.getMethod());
    assertEquals("/test", recordedRequest.getPath());

    response.close();
  }

  @Test
  void testPostRequest() throws IOException, InterruptedException {
    server.enqueue(new MockResponse()
        .setResponseCode(201)
        .setBody("created"));

    HttpUrl url = HttpUrl.parse(server.url("/api/data").toString());
    HttpRequestBody body = HttpRequestBody.of("{\"key\":\"value\"}");
    HttpRequest request = HttpRequest.newBuilder()
        .url(url)
        .header("Content-Type", "application/json")
        .post(body)
        .build();

    HttpResponse response = client.execute(request);

    assertEquals(201, response.code());

    RecordedRequest recordedRequest = server.takeRequest();
    assertEquals("POST", recordedRequest.getMethod());
    assertEquals("/api/data", recordedRequest.getPath());
    assertEquals("application/json", recordedRequest.getHeader("Content-Type"));
    assertEquals("{\"key\":\"value\"}", recordedRequest.getBody().readUtf8());

    response.close();
  }

  @Test
  void testErrorResponse() throws IOException {
    server.enqueue(new MockResponse()
        .setResponseCode(404)
        .setBody("not found"));

    HttpUrl url = HttpUrl.parse(server.url("/missing").toString());
    HttpRequest request = HttpRequest.newBuilder()
        .url(url)
        .get()
        .build();

    HttpResponse response = client.execute(request);

    assertEquals(404, response.code());
    assertTrue(!response.isSuccessful());

    response.close();
  }

  @Test
  void testRequestHeaders() throws IOException, InterruptedException {
    server.enqueue(new MockResponse()
        .setResponseCode(200));

    HttpUrl url = HttpUrl.parse(server.url("/test").toString());
    HttpRequest request = HttpRequest.newBuilder()
        .url(url)
        .header("X-Custom-Header", "custom-value")
        .header("Accept", "application/json")
        .get()
        .build();

    HttpResponse response = client.execute(request);

    assertEquals(200, response.code());

    RecordedRequest recordedRequest = server.takeRequest();
    assertEquals("custom-value", recordedRequest.getHeader("X-Custom-Header"));
    assertEquals("application/json", recordedRequest.getHeader("Accept"));

    response.close();
  }

  @Test
  void testResponseHeaders() throws IOException {
    server.enqueue(new MockResponse()
        .setResponseCode(200)
        .addHeader("X-Server-Header", "server-value")
        .addHeader("Content-Type", "text/plain")
        .setBody("test"));

    HttpUrl url = HttpUrl.parse(server.url("/test").toString());
    HttpRequest request = HttpRequest.newBuilder()
        .url(url)
        .get()
        .build();

    HttpResponse response = client.execute(request);

    assertEquals("server-value", response.header("X-Server-Header"));
    assertEquals("text/plain", response.header("Content-Type"));

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

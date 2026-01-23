package datadog.communication.http.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HttpClientBuilderTest {

  private MockWebServer server;

  @BeforeEach
  void setUp() throws IOException {
    server = new MockWebServer();
    server.start();
  }

  @AfterEach
  void tearDown() throws IOException {
    server.shutdown();
  }

  @Test
  void testDefaultBuilder() {
    HttpClient client = HttpClient.newBuilder()
        .build();

    assertNotNull(client);
  }

  @Test
  void testConnectTimeout() throws IOException {
    HttpClient client = HttpClient.newBuilder()
        .connectTimeout(5000, TimeUnit.MILLISECONDS)
        .build();

    assertNotNull(client);

    // Verify client works with configured timeout
    server.enqueue(new MockResponse().setResponseCode(200));
    HttpUrl url = HttpUrl.parse(server.url("/test").toString());
    HttpRequest request = HttpRequest.newBuilder().url(url).get().build();
    HttpResponse response = client.execute(request);
    assertEquals(200, response.code());
    response.close();
  }

  @Test
  void testReadTimeout() throws IOException {
    HttpClient client = HttpClient.newBuilder()
        .readTimeout(3000, TimeUnit.MILLISECONDS)
        .build();

    assertNotNull(client);

    server.enqueue(new MockResponse().setResponseCode(200));
    HttpUrl url = HttpUrl.parse(server.url("/test").toString());
    HttpRequest request = HttpRequest.newBuilder().url(url).get().build();
    HttpResponse response = client.execute(request);
    assertEquals(200, response.code());
    response.close();
  }

  @Test
  void testWriteTimeout() throws IOException {
    HttpClient client = HttpClient.newBuilder()
        .writeTimeout(3000, TimeUnit.MILLISECONDS)
        .build();

    assertNotNull(client);

    server.enqueue(new MockResponse().setResponseCode(200));
    HttpUrl url = HttpUrl.parse(server.url("/test").toString());
    HttpRequest request = HttpRequest.newBuilder().url(url).get().build();
    HttpResponse response = client.execute(request);
    assertEquals(200, response.code());
    response.close();
  }

  @Test
  void testAllTimeouts() throws IOException {
    HttpClient client = HttpClient.newBuilder()
        .connectTimeout(5000, TimeUnit.MILLISECONDS)
        .readTimeout(3000, TimeUnit.MILLISECONDS)
        .writeTimeout(3000, TimeUnit.MILLISECONDS)
        .build();

    assertNotNull(client);

    server.enqueue(new MockResponse().setResponseCode(200));
    HttpUrl url = HttpUrl.parse(server.url("/test").toString());
    HttpRequest request = HttpRequest.newBuilder().url(url).get().build();
    HttpResponse response = client.execute(request);
    assertEquals(200, response.code());
    response.close();
  }

  @Test
  void testClearText() throws IOException {
    // HTTP (not HTTPS) server
    HttpClient client = HttpClient.newBuilder()
        .clearText(true)
        .build();

    assertNotNull(client);

    server.enqueue(new MockResponse().setResponseCode(200));
    HttpUrl url = HttpUrl.parse(server.url("/test").toString());
    HttpRequest request = HttpRequest.newBuilder().url(url).get().build();
    HttpResponse response = client.execute(request);
    assertEquals(200, response.code());
    response.close();
  }

  @Test
  void testRetryOnConnectionFailure() {
    HttpClient client = HttpClient.newBuilder()
        .retryOnConnectionFailure(true)
        .build();

    assertNotNull(client);
  }

  @Test
  void testMaxRequests() throws IOException {
    HttpClient client = HttpClient.newBuilder()
        .maxRequests(10)
        .build();

    assertNotNull(client);

    server.enqueue(new MockResponse().setResponseCode(200));
    HttpUrl url = HttpUrl.parse(server.url("/test").toString());
    HttpRequest request = HttpRequest.newBuilder().url(url).get().build();
    HttpResponse response = client.execute(request);
    assertEquals(200, response.code());
    response.close();
  }

  @Test
  void testCustomDispatcher() throws IOException {
    // Use ExecutorService (required by OkHttp)
    Executor executor = java.util.concurrent.Executors.newSingleThreadExecutor();

    HttpClient client = HttpClient.newBuilder()
        .dispatcher(executor)
        .build();

    assertNotNull(client);

    server.enqueue(new MockResponse().setResponseCode(200));
    HttpUrl url = HttpUrl.parse(server.url("/test").toString());
    HttpRequest request = HttpRequest.newBuilder().url(url).get().build();
    HttpResponse response = client.execute(request);
    assertEquals(200, response.code());
    response.close();
  }

  @Test
  void testEventListener() throws IOException {
    TestListener listener = new TestListener();

    HttpClient client = HttpClient.newBuilder()
        .eventListener(listener)
        .build();

    assertNotNull(client);

    server.enqueue(new MockResponse().setResponseCode(200));
    HttpUrl url = HttpUrl.parse(server.url("/test").toString());
    HttpRequest request = HttpRequest.newBuilder().url(url).get().build();
    HttpResponse response = client.execute(request);
    assertEquals(200, response.code());
    response.close();

    // Listener should have been called
    assertTrue(listener.requestStarted);
    assertTrue(listener.requestEnded);
  }

  @Test
  void testProxyConfiguration() {
    Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress("proxy.example.com", 8080));

    HttpClient client = HttpClient.newBuilder()
        .proxy(proxy)
        .build();

    assertNotNull(client);
  }

  @Test
  void testProxyWithAuthentication() {
    Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress("proxy.example.com", 8080));

    HttpClient client = HttpClient.newBuilder()
        .proxy(proxy)
        .proxyAuthenticator("username", "password")
        .build();

    assertNotNull(client);
  }

  @Test
  void testUnixDomainSocket() {
    File socketFile = new File("/tmp/test.sock");

    HttpClient client = HttpClient.newBuilder()
        .unixDomainSocket(socketFile)
        .build();

    assertNotNull(client);
  }

  @Test
  void testNamedPipe() {
    HttpClient client = HttpClient.newBuilder()
        .namedPipe("\\\\.\\pipe\\test")
        .build();

    assertNotNull(client);
  }

  @Test
  void testChainedConfiguration() throws IOException {
    HttpClient client = HttpClient.newBuilder()
        .connectTimeout(5000, TimeUnit.MILLISECONDS)
        .readTimeout(3000, TimeUnit.MILLISECONDS)
        .writeTimeout(3000, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .maxRequests(20)
        .clearText(true)
        .build();

    assertNotNull(client);

    // Verify it works
    server.enqueue(new MockResponse().setResponseCode(200));
    HttpUrl url = HttpUrl.parse(server.url("/test").toString());
    HttpRequest request = HttpRequest.newBuilder().url(url).get().build();
    HttpResponse response = client.execute(request);
    assertEquals(200, response.code());
    response.close();
  }

  private static class TestListener implements HttpListener {
    boolean requestStarted = false;
    boolean requestEnded = false;
    boolean requestFailed = false;

    @Override
    public void onRequestStart(HttpRequest request) {
      requestStarted = true;
    }

    @Override
    public void onRequestEnd(HttpRequest request, HttpResponse response) {
      requestEnded = true;
    }

    @Override
    public void onRequestFailure(HttpRequest request, IOException exception) {
      requestFailed = true;
    }
  }
}

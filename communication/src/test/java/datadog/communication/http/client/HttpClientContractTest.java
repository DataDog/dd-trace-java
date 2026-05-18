package datadog.communication.http.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import datadog.communication.http.HttpRetryPolicy;
import datadog.communication.http.client.ahc.ApacheAsyncHttpClientBuilder;
import datadog.communication.http.client.jetty.JettyHttpClientBuilder;
import datadog.communication.http.client.netty.NettyHttpClientBuilder;
import io.netty.channel.nio.NioEventLoopGroup;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.opentest4j.TestAbortedException;

@ExtendWith(HttpClientContractTest.ClientImplementationExtension.class)
public class HttpClientContractTest {
  public static final String CLIENT_IMPL_PARAMETER = "datadog.http.client.impl";
  public static final String NETTY = "netty";
  public static final String AHC = "ahc";
  public static final String JETTY = "jetty";

  private final List<Closeable> closeables = new ArrayList<>();
  private MockWebServer server;
  private HttpClientBuilder<?> builder;

  private void useBuilder(HttpClientBuilder<?> implementation) {
    this.builder = implementation;
  }

  private String clientName() {
    return builder.getClass().getSimpleName();
  }
  
  private boolean supportsUnixDomainSocket() {
    return isNetty() || isJetty();
  }

  private boolean isNetty() {
    return clientName().equals(NettyHttpClientBuilder.class.getSimpleName());
  }

  private boolean isJetty() {
    return clientName().equals(JettyHttpClientBuilder.class.getSimpleName());
  }

  @AfterEach
  void afterEach() {
    if (server != null) {
      try {
        server.shutdown();
      } catch (Exception ignored) {
      }
    }

    for (int i = closeables.size() - 1; i >= 0; i--) {
      try {
        closeables.get(i).close();
      } catch (Exception ignored) {
      }
    }
    closeables.clear();
  }

  @Test
  void shouldSendRequestAndReceiveResponse() throws Exception {
    server = new MockWebServer();
    server.enqueue(new MockResponse().setResponseCode(200).setBody("pong"));
    server.start();

    URI uri = server.url("/ping").uri();
    HttpClientRequest request =
        HttpClientRequest.builder(uri, "GET").addHeader("x-test", "1").build();

    try (HttpClient client = builder.build()) {
      HttpClientResponse response = client.execute(request);
      assertEquals(200, response.statusCode(), clientName());
      assertArrayEquals("pong".getBytes(StandardCharsets.UTF_8), response.body(), clientName());
    }
  }

  @Test
  void shouldRetryOnServerError() throws Exception {
    server = new MockWebServer();
    server.enqueue(new MockResponse().setResponseCode(500));
    server.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));
    server.start();

    URI uri = server.url("/retry").uri();
    HttpClientRequest request = HttpClientRequest.builder(uri, "POST").body(new byte[] {1}).build();

    HttpRetryPolicy.Factory retryPolicy = new HttpRetryPolicy.Factory(1, 1, 1.0);
    try (HttpClient client = builder.retryPolicyFactory(retryPolicy).build()) {
      HttpClientResponse response = client.execute(request);
      assertEquals(200, response.statusCode(), clientName());
      assertEquals(2, server.getRequestCount(), clientName());
    }
  }

  @Test
  void shouldRetryOn429WithRateLimitReset() throws Exception {
    server = new MockWebServer();
    server.enqueue(new MockResponse().setResponseCode(429).setHeader("x-ratelimit-reset", "0"));
    server.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));
    server.start();

    URI uri = server.url("/retry-429").uri();
    HttpClientRequest request = HttpClientRequest.builder(uri, "POST").body(new byte[] {1}).build();

    HttpRetryPolicy.Factory retryPolicy = new HttpRetryPolicy.Factory(2, 1, 1.0);
    try (HttpClient client = builder.retryPolicyFactory(retryPolicy).build()) {
      HttpClientResponse response = client.execute(request);
      assertEquals(200, response.statusCode(), clientName());
      assertEquals(2, server.getRequestCount(), clientName());
    }
  }

  @Test
  void shouldRetryAfterConnectionIssue() throws Exception {
    ServerSocket reservedPort = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
    int port = reservedPort.getLocalPort();
    reservedPort.close();

    URI uri = new URI("http://127.0.0.1:" + port + "/retry-connection");
    HttpClientRequest request = HttpClientRequest.builder(uri, "GET").build();

    HttpRetryPolicy.Factory retryPolicy = new HttpRetryPolicy.Factory(2, 200, 1.0);
    try (HttpClient client =
        builder
            .retryPolicyFactory(retryPolicy)
            .connectTimeoutMillis(100)
            .requestTimeoutMillis(2_000)
            .build()) {
      long startNanos = System.nanoTime();
      IOException exception = assertThrows(IOException.class, () -> client.execute(request));
      long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
      assertTrue(elapsedMillis >= 100, "retry backoff should have been applied for " + clientName());
      assertNotNull(exception.getMessage(), clientName());
    }
  }

  @Test
  void shouldSendRequestThroughProxy() throws Exception {
    server = new MockWebServer();
    server.enqueue(new MockResponse().setResponseCode(200).setBody("proxied"));
    server.start();

    MockProxy proxy = new MockProxy();
    closeables.add(proxy);
    proxy.start();

    URI uri = server.url("/proxy").uri();
    HttpClientRequest request = HttpClientRequest.builder(uri, "GET").build();

    try (HttpClient client = builder.proxy("127.0.0.1", proxy.port()).build()) {
      HttpClientResponse response = client.execute(request);
      assertEquals(200, response.statusCode(), clientName());
      assertArrayEquals("proxied".getBytes(StandardCharsets.UTF_8), response.body(), clientName());
    }

    assertTrue(proxy.awaitConnect(5, TimeUnit.SECONDS), clientName());
    assertTrue(
        proxy.connectRequestLine().startsWith("CONNECT ")
            || proxy.connectRequestLine().startsWith("GET "),
        clientName());
    assertEquals(1, server.getRequestCount(), clientName());
  }

  @Test
  void shouldSendProxyAuthorizationWhenCredentialsProvided() throws Exception {
    server = new MockWebServer();
    server.enqueue(new MockResponse().setResponseCode(200).setBody("proxied-auth"));
    server.start();

    MockProxy proxy = new MockProxy(true);
    closeables.add(proxy);
    proxy.start();

    URI uri = server.url("/proxy-auth").uri();
    HttpClientRequest request = HttpClientRequest.builder(uri, "GET").build();

    try (HttpClient client =
        builder.proxy("127.0.0.1", proxy.port(), "test-user", "test-pass").build()) {
      HttpClientResponse response = client.execute(request);
      assertEquals(200, response.statusCode(), clientName());
      assertArrayEquals(
          "proxied-auth".getBytes(StandardCharsets.UTF_8), response.body(), clientName());
    }

    assertTrue(proxy.awaitConnect(5, TimeUnit.SECONDS), clientName());
    String authHeader = proxy.connectHeader("Proxy-Authorization");
    assertNotNull(authHeader, clientName());
    String expectedAuth =
        "Basic "
            + Base64.getEncoder()
                .encodeToString("test-user:test-pass".getBytes(StandardCharsets.US_ASCII));
    assertEquals(expectedAuth, authHeader, clientName());
  }

  @Test
  void shouldTimeoutWhenServerDoesNotRespond() throws Exception {
    server = new MockWebServer();
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
    server.start();

    URI uri = server.url("/timeout").uri();
    HttpClientRequest request = HttpClientRequest.builder(uri, "GET").build();

    try (HttpClient client =
        builder.requestTimeoutMillis(250).connectTimeoutMillis(250).build()) {
      IOException exception = assertThrows(IOException.class, () -> client.execute(request));
      assertTrue(
          exception.getMessage().contains("timeout")
              || (exception.getCause() != null
                  && exception.getCause().getMessage() != null
                  && exception.getCause().getMessage().contains("timeout")),
          clientName());
    }
  }

  @Test
  void shouldFailInFlightRequestWhenClientClosed() throws Exception {
    server = new MockWebServer();
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
    server.start();

    URI uri = server.url("/close-in-flight").uri();
    HttpClientRequest request = HttpClientRequest.builder(uri, "GET").build();

    try (HttpClient client = builder.requestTimeoutMillis(30_000).connectTimeoutMillis(500).build()) {
      CompletableFuture<HttpClientResponse> responseFuture =
          CompletableFuture.supplyAsync(
              () -> {
                try {
                  return client.execute(request);
                } catch (IOException e) {
                  throw new RuntimeException(e);
                }
              });

      Thread.sleep(200);
      client.close();

      ExecutionException exception =
          assertThrows(ExecutionException.class, () -> responseFuture.get(5, TimeUnit.SECONDS));
      assertNotNull(exception.getCause(), clientName());
      assertTrue(exception.getCause() instanceof RuntimeException, clientName());
      assertTrue(exception.getCause().getCause() instanceof IOException, clientName());
    }
  }

  @Test
  void shouldNotCloseExternallyManagedEventLoopGroupWhenSupported() {
    assumeTrue(isNetty(), "Netty-specific lifecycle test");

    NioEventLoopGroup externalGroup = new NioEventLoopGroup(1);
    try (HttpClient ignored =
        new NettyHttpClientBuilder().eventLoopGroup(externalGroup, false).build()) {
      // no-op
    }
    assertFalse(externalGroup.isShuttingDown());
    externalGroup.shutdownGracefully().awaitUninterruptibly();
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void shouldSendRequestThroughUnixDomainSocketWhenSupported() throws Exception {
    assumeTrue(supportsUnixDomainSocket(), "UDS supported only by implementations that expose it");

    Path socketPath = Files.createTempFile("http-client-contract", ".sock");
    Files.deleteIfExists(socketPath);
    server = new MockWebServer();
    server.setServerSocketFactory(new UnixDomainServerSocketFactory(socketPath));
    server.enqueue(new MockResponse().setResponseCode(200).setBody("uds-ok"));
    server.start();

    URI uri = new URI("http://localhost/uds");
    HttpClientRequest request = HttpClientRequest.builder(uri, "GET").build();

    try (HttpClient client =
        builder
            .transport(HttpTransport.UNIX_DOMAIN_SOCKET)
            .unixDomainSocketPath(socketPath.toString())
            .build()) {
      HttpClientResponse response = client.execute(request);
      assertEquals(200, response.statusCode(), clientName());
      assertArrayEquals("uds-ok".getBytes(StandardCharsets.UTF_8), response.body(), clientName());
    }
  }

  @Test
  void shouldFailImmediatelyWhenUnixDomainSocketIsUnsupported() {
    assumeFalse(supportsUnixDomainSocket(), "Only run for implementations without UDS support");

    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> builder.transport(HttpTransport.UNIX_DOMAIN_SOCKET));
    assertTrue(exception.getMessage().contains("TCP"), clientName());
  }

  static final class ClientImplementationExtension implements BeforeEachCallback {
    @Override
    public void beforeEach(ExtensionContext context) {
      String implementationValue = context.getConfigurationParameter(CLIENT_IMPL_PARAMETER).orElse(null);
      if (implementationValue == null || implementationValue.isEmpty()) {
        throw new TestAbortedException(
            "Skipping contract test: missing JUnit configuration parameter: "
                + CLIENT_IMPL_PARAMETER);
      }
      switch (implementationValue) {
        case NETTY:
          ((HttpClientContractTest) context.getRequiredTestInstance())
              .useBuilder(new NettyHttpClientBuilder());
          break;
        case AHC:
          ((HttpClientContractTest) context.getRequiredTestInstance())
              .useBuilder(new ApacheAsyncHttpClientBuilder());
          break;
        case JETTY:
          ((HttpClientContractTest) context.getRequiredTestInstance())
              .useBuilder(new JettyHttpClientBuilder());
          break;
        default:
          throw new IllegalStateException("Unsupported implementation: " + implementationValue);
      }
    }
  }
}

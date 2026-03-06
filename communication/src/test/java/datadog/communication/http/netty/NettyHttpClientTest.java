package datadog.communication.http.netty;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.communication.http.HttpRetryPolicy;
import datadog.communication.http.client.HttpClientFacade;
import datadog.communication.http.client.HttpClientRequest;
import datadog.communication.http.client.HttpClientResponse;
import datadog.communication.http.client.HttpTransport;
import io.netty.channel.nio.NioEventLoopGroup;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import jnr.unixsocket.UnixServerSocketChannel;
import jnr.unixsocket.UnixSocketAddress;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

class NettyHttpClientTest {

  private MockWebServer server;
  private Closeable closeable;

  @AfterEach
  void afterEach() {
    if (server != null) {
      try {
        server.shutdown();
      } catch (Exception ignored) {
      }
    }
    if (closeable != null) {
      try {
        closeable.close();
      } catch (Exception ignored) {
      }
    }
  }

  @Test
  void shouldSendRequestAndReceiveResponse() throws Exception {
    server = new MockWebServer();
    server.enqueue(new MockResponse().setResponseCode(200).setBody("pong"));
    server.start();

    URI uri = server.url("/ping").uri();
    HttpClientRequest request =
        HttpClientRequest.builder(uri, "GET").addHeader("x-test", "1").build();

    try (HttpClientFacade client = NettyHttpClientFactory.builder().build()) {
      HttpClientResponse response = client.execute(request);
      assertEquals(200, response.statusCode());
      assertArrayEquals("pong".getBytes(StandardCharsets.UTF_8), response.body());
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
    try (HttpClientFacade client =
        NettyHttpClientFactory.builder().retryPolicyFactory(retryPolicy).build()) {
      HttpClientResponse response = client.execute(request);
      assertEquals(200, response.statusCode());
      assertEquals(2, server.getRequestCount());
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
    try (HttpClientFacade client =
        NettyHttpClientFactory.builder().retryPolicyFactory(retryPolicy).build()) {
      HttpClientResponse response = client.execute(request);
      assertEquals(200, response.statusCode());
      assertEquals(2, server.getRequestCount());
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
    try (HttpClientFacade client =
        NettyHttpClientFactory.builder()
            .retryPolicyFactory(retryPolicy)
            .connectTimeoutMillis(100)
            .requestTimeoutMillis(2_000)
            .build()) {
      long startNanos = System.nanoTime();
      IOException exception = assertThrows(IOException.class, () -> client.execute(request));
      long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
      assertTrue(elapsedMillis >= 180, "retry backoff should have been applied");
      assertNotNull(exception.getMessage());
    }
  }

  @Test
  void shouldNotCloseExternallyManagedEventLoopGroup() throws Exception {
    NioEventLoopGroup externalGroup = new NioEventLoopGroup(1);

    try (HttpClientFacade client =
        NettyHttpClientFactory.builder().eventLoopGroup(externalGroup, false).build()) {
      // no-op
    }

    assertFalse(externalGroup.isShuttingDown());
    externalGroup.shutdownGracefully().awaitUninterruptibly();
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void shouldSendRequestThroughUnixDomainSocket() throws Exception {
    Path socketPath = Files.createTempFile("netty-http-client", ".sock");
    Files.deleteIfExists(socketPath);
    UnixDomainHttpServer udsServer = new UnixDomainHttpServer(socketPath, "uds-ok");
    closeable = udsServer;
    udsServer.start();

    URI uri = new URI("http://localhost/uds");
    HttpClientRequest request = HttpClientRequest.builder(uri, "GET").build();

    try (HttpClientFacade client =
        NettyHttpClientFactory.builder()
            .transport(HttpTransport.UNIX_DOMAIN_SOCKET)
            .unixDomainSocketPath(socketPath.toString())
            .build()) {
      HttpClientResponse response = client.execute(request);
      assertEquals(200, response.statusCode());
      assertArrayEquals("uds-ok".getBytes(StandardCharsets.UTF_8), response.body());
    }
  }

  @Test
  void shouldSendRequestThroughProxy() throws Exception {
    server = new MockWebServer();
    server.enqueue(new MockResponse().setResponseCode(200).setBody("proxied"));
    server.start();

    ProxyTunnelServer proxy = new ProxyTunnelServer();
    closeable = proxy;
    proxy.start();

    URI uri = server.url("/proxy").uri();
    HttpClientRequest request = HttpClientRequest.builder(uri, "GET").build();

    try (HttpClientFacade client =
        NettyHttpClientFactory.builder().proxy("127.0.0.1", proxy.port()).build()) {
      HttpClientResponse response = client.execute(request);
      assertEquals(200, response.statusCode());
      assertArrayEquals("proxied".getBytes(StandardCharsets.UTF_8), response.body());
    }

    assertTrue(proxy.awaitConnect(5, TimeUnit.SECONDS));
    assertTrue(proxy.connectRequestLine().startsWith("CONNECT "));
    assertEquals(1, server.getRequestCount());
  }

  @Test
  void shouldSendProxyAuthorizationWhenCredentialsProvided() throws Exception {
    server = new MockWebServer();
    server.enqueue(new MockResponse().setResponseCode(200).setBody("proxied-auth"));
    server.start();

    ProxyTunnelServer proxy = new ProxyTunnelServer();
    closeable = proxy;
    proxy.start();

    URI uri = server.url("/proxy-auth").uri();
    HttpClientRequest request = HttpClientRequest.builder(uri, "GET").build();

    try (HttpClientFacade client =
        NettyHttpClientFactory.builder()
            .proxy("127.0.0.1", proxy.port(), "test-user", "test-pass")
            .build()) {
      HttpClientResponse response = client.execute(request);
      assertEquals(200, response.statusCode());
      assertArrayEquals("proxied-auth".getBytes(StandardCharsets.UTF_8), response.body());
    }

    assertTrue(proxy.awaitConnect(5, TimeUnit.SECONDS));
    String authHeader = proxy.connectHeader("Proxy-Authorization");
    assertNotNull(authHeader);
    String expectedAuth =
        "Basic "
            + Base64.getEncoder()
                .encodeToString("test-user:test-pass".getBytes(StandardCharsets.US_ASCII));
    assertEquals(expectedAuth, authHeader);
  }

  @Test
  void shouldTimeoutWhenServerDoesNotRespond() throws Exception {
    server = new MockWebServer();
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
    server.start();

    URI uri = server.url("/timeout").uri();
    HttpClientRequest request = HttpClientRequest.builder(uri, "GET").build();

    try (HttpClientFacade client =
        NettyHttpClientFactory.builder()
            .requestTimeoutMillis(250)
            .readTimeoutMillis(250)
            .writeTimeoutMillis(250)
            .connectTimeoutMillis(250)
            .build()) {
      IOException exception = assertThrows(IOException.class, () -> client.execute(request));
      assertTrue(
          exception.getMessage().contains("timeout")
              || (exception.getCause() != null
                  && exception.getCause().getMessage() != null
                  && exception.getCause().getMessage().contains("timeout")));
    }
  }

  @Test
  void shouldFailInFlightRequestWhenClientClosed() throws Exception {
    server = new MockWebServer();
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
    server.start();

    URI uri = server.url("/close-in-flight").uri();
    HttpClientRequest request = HttpClientRequest.builder(uri, "GET").build();

    HttpClientFacade client =
        NettyHttpClientFactory.builder()
            .requestTimeoutMillis(30_000)
            .readTimeoutMillis(30_000)
            .writeTimeoutMillis(30_000)
            .connectTimeoutMillis(500)
            .build();

    try {
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
      assertNotNull(exception.getCause());
      assertTrue(exception.getCause() instanceof RuntimeException);
      assertTrue(exception.getCause().getCause() instanceof IOException);
    } finally {
      client.close();
    }
  }

  private static final class UnixDomainHttpServer implements Closeable {
    private final Path socketPath;
    private final String body;
    private final UnixServerSocketChannel serverChannel;
    private Thread serverThread;

    private UnixDomainHttpServer(Path socketPath, String body) throws IOException {
      this.socketPath = socketPath;
      this.body = body;
      this.serverChannel = UnixServerSocketChannel.open();
      this.serverChannel.socket().bind(new UnixSocketAddress(socketPath.toFile()));
    }

    private void start() {
      serverThread =
          new Thread(
              () -> {
                try (jnr.unixsocket.UnixSocketChannel channel = serverChannel.accept()) {
                  OutputStream outputStream = channel.socket().getOutputStream();
                  byte[] responseBody = body.getBytes(StandardCharsets.UTF_8);
                  String response =
                      "HTTP/1.1 200 OK\r\n"
                          + "Content-Length: "
                          + responseBody.length
                          + "\r\n"
                          + "Connection: close\r\n\r\n";
                  outputStream.write(response.getBytes(StandardCharsets.US_ASCII));
                  outputStream.write(responseBody);
                  outputStream.flush();
                } catch (Exception ignored) {
                }
              },
              "uds-http-server");
      serverThread.setDaemon(true);
      serverThread.start();
    }

    @Override
    public void close() throws IOException {
      try {
        serverChannel.close();
      } finally {
        Files.deleteIfExists(socketPath);
      }
    }
  }

  private static final class ProxyTunnelServer implements Closeable {
    private final ServerSocket serverSocket;
    private final CountDownLatch connectLatch = new CountDownLatch(1);
    private final AtomicReference<String> connectLine = new AtomicReference<>();
    private final AtomicReference<Map<String, String>> connectHeaders =
        new AtomicReference<>(Collections.<String, String>emptyMap());
    private volatile boolean running = true;
    private Thread acceptThread;

    private ProxyTunnelServer() throws IOException {
      serverSocket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
    }

    private int port() {
      return serverSocket.getLocalPort();
    }

    private void start() {
      acceptThread =
          new Thread(
              () -> {
                while (running) {
                  try {
                    Socket clientSocket = serverSocket.accept();
                    handleClient(clientSocket);
                  } catch (IOException ignored) {
                    return;
                  }
                }
              },
              "proxy-tunnel-server");
      acceptThread.setDaemon(true);
      acceptThread.start();
    }

    private void handleClient(Socket clientSocket) {
      Thread handler =
          new Thread(
              () -> {
                try (Socket client = clientSocket) {
                  BufferedReader reader =
                      new BufferedReader(
                          new InputStreamReader(
                              client.getInputStream(), StandardCharsets.US_ASCII));
                  OutputStream clientOut = client.getOutputStream();

                  String firstLine = reader.readLine();
                  connectLine.set(firstLine);
                  connectLatch.countDown();

                  if (firstLine == null || !firstLine.startsWith("CONNECT ")) {
                    return;
                  }

                  Map<String, String> headers = new LinkedHashMap<String, String>();
                  while (true) {
                    String line = reader.readLine();
                    if (line == null || line.isEmpty()) {
                      break;
                    }
                    int idx = line.indexOf(':');
                    if (idx > 0) {
                      headers.put(line.substring(0, idx).trim(), line.substring(idx + 1).trim());
                    }
                  }
                  connectHeaders.set(headers);

                  String authority = firstLine.split(" ")[1];
                  String[] hostPort = authority.split(":");
                  try (Socket upstream = new Socket(hostPort[0], Integer.parseInt(hostPort[1]))) {
                    clientOut.write(
                        "HTTP/1.1 200 Connection established\r\nProxy-Agent: test\r\n\r\n"
                            .getBytes(StandardCharsets.US_ASCII));
                    clientOut.flush();

                    Thread upstreamToClient =
                        new Thread(
                            () -> transfer(upstream, client), "proxy-upstream-to-client-thread");
                    upstreamToClient.setDaemon(true);
                    upstreamToClient.start();
                    transfer(client, upstream);
                  }
                } catch (Exception ignored) {
                }
              },
              "proxy-client-handler");
      handler.setDaemon(true);
      handler.start();
    }

    private static void transfer(Socket source, Socket destination) {
      try {
        InputStream inputStream = source.getInputStream();
        OutputStream outputStream = destination.getOutputStream();
        byte[] buffer = new byte[8 * 1024];
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
          outputStream.write(buffer, 0, read);
          outputStream.flush();
        }
      } catch (IOException ignored) {
      }
    }

    private boolean awaitConnect(long timeout, TimeUnit unit) throws InterruptedException {
      return connectLatch.await(timeout, unit);
    }

    private String connectRequestLine() {
      return connectLine.get();
    }

    private String connectHeader(String name) {
      Map<String, String> headers = connectHeaders.get();
      for (Map.Entry<String, String> entry : headers.entrySet()) {
        if (entry.getKey().equalsIgnoreCase(name)) {
          return entry.getValue();
        }
      }
      return null;
    }

    @Override
    public void close() throws IOException {
      running = false;
      serverSocket.close();
    }
  }

  private static void drainHeaders(InputStream inputStream) throws IOException {}
}

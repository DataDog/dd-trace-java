package datadog.communication.http.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.communication.http.HttpRetryPolicy;
import datadog.communication.http.ahc.ApacheAsyncHttpClientFactory;
import datadog.communication.http.netty.NettyHttpClientFactory;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
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
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.opentest4j.TestAbortedException;

@ExtendWith(HttpClientFacadeContractTest.ClientImplementationExtension.class)
public class HttpClientFacadeContractTest {
  public static final String CLIENT_IMPL_PARAMETER = "datadog.http.client.impl";

  private final List<Closeable> closeables = new ArrayList<>();
  private MockWebServer server;
  private ClientImplementation implementation;

  private void setImplementation(ClientImplementation implementation) {
    this.implementation = implementation;
  }

  private String clientName() {
    return implementation.id;
  }

  private HttpClientFacadeBuilder<?> newBuilder() {
    switch (implementation) {
      case NETTY:
        return NettyHttpClientFactory.builder();
      case APACHE_ASYNC_HTTP_CLIENT5:
        return ApacheAsyncHttpClientFactory.builder();
      default:
        throw new IllegalStateException("Unsupported implementation: " + implementation);
    }
  }

  private boolean supportsUnixDomainSocket() {
    return implementation == ClientImplementation.NETTY;
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

    try (HttpClientFacade client = newBuilder().build()) {
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
    try (HttpClientFacade client = newBuilder().retryPolicyFactory(retryPolicy).build()) {
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
    try (HttpClientFacade client = newBuilder().retryPolicyFactory(retryPolicy).build()) {
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
    try (HttpClientFacade client =
        newBuilder()
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

    ProxyTunnelServer proxy = new ProxyTunnelServer();
    closeables.add(proxy);
    proxy.start();

    URI uri = server.url("/proxy").uri();
    HttpClientRequest request = HttpClientRequest.builder(uri, "GET").build();

    try (HttpClientFacade client = newBuilder().proxy("127.0.0.1", proxy.port()).build()) {
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

    ProxyTunnelServer proxy = new ProxyTunnelServer(true);
    closeables.add(proxy);
    proxy.start();

    URI uri = server.url("/proxy-auth").uri();
    HttpClientRequest request = HttpClientRequest.builder(uri, "GET").build();

    try (HttpClientFacade client =
        newBuilder().proxy("127.0.0.1", proxy.port(), "test-user", "test-pass").build()) {
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

    try (HttpClientFacade client =
        newBuilder().requestTimeoutMillis(250).connectTimeoutMillis(250).build()) {
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

    HttpClientFacade client =
        newBuilder().requestTimeoutMillis(30_000).connectTimeoutMillis(500).build();

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
      assertNotNull(exception.getCause(), clientName());
      assertTrue(exception.getCause() instanceof RuntimeException, clientName());
      assertTrue(exception.getCause().getCause() instanceof IOException, clientName());
    } finally {
      client.close();
    }
  }

  @Test
  void shouldNotCloseExternallyManagedEventLoopGroupWhenSupported() throws Exception {
    if (implementation != ClientImplementation.NETTY) {
      return;
    }

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
  void shouldSendRequestThroughUnixDomainSocketWhenSupported() throws Exception {
    if (!supportsUnixDomainSocket()) {
      return;
    }

    Path socketPath = Files.createTempFile("http-client-contract", ".sock");
    Files.deleteIfExists(socketPath);
    UnixDomainHttpServer udsServer = new UnixDomainHttpServer(socketPath, "uds-ok");
    closeables.add(udsServer);
    udsServer.start();

    URI uri = new URI("http://localhost/uds");
    HttpClientRequest request = HttpClientRequest.builder(uri, "GET").build();

    try (HttpClientFacade client =
        newBuilder()
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
    if (supportsUnixDomainSocket()) {
      return;
    }

    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> newBuilder().transport(HttpTransport.UNIX_DOMAIN_SOCKET));
    assertTrue(exception.getMessage().contains("TCP"), clientName());
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
    private final AtomicReference<String> connectLine = new AtomicReference<String>();
    private final AtomicReference<Map<String, String>> connectHeaders =
        new AtomicReference<Map<String, String>>(Collections.<String, String>emptyMap());
    private volatile boolean running = true;
    private final boolean requireAuth;

    private ProxyTunnelServer() throws IOException {
      this(false);
    }

    private ProxyTunnelServer(boolean requireAuth) throws IOException {
      this.requireAuth = requireAuth;
      serverSocket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
    }

    private int port() {
      return serverSocket.getLocalPort();
    }

    private void start() {
      Thread acceptThread =
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
                    handleForwardProxyRequest(firstLine, reader, clientOut);
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
                  if (requireAuth && connectHeaderFrom(headers, "Proxy-Authorization") == null) {
                    clientOut.write(
                        "HTTP/1.1 407 Proxy Authentication Required\r\n"
                            .getBytes(StandardCharsets.US_ASCII));
                    clientOut.write(
                        "Proxy-Authenticate: Basic realm=\"test\"\r\n\r\n"
                            .getBytes(StandardCharsets.US_ASCII));
                    clientOut.flush();
                    return;
                  }

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

    private void handleForwardProxyRequest(
        String firstLine, BufferedReader reader, OutputStream clientOut) throws IOException {
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
      if (requireAuth && connectHeaderFrom(headers, "Proxy-Authorization") == null) {
        clientOut.write(
            "HTTP/1.1 407 Proxy Authentication Required\r\n".getBytes(StandardCharsets.US_ASCII));
        clientOut.write(
            "Proxy-Authenticate: Basic realm=\"test\"\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        clientOut.flush();
        return;
      }

      String[] parts = firstLine.split(" ");
      URI target = URI.create(parts[1]);
      int port = target.getPort() == -1 ? 80 : target.getPort();
      String path = target.getRawPath();
      if (path == null || path.isEmpty()) {
        path = "/";
      }
      if (target.getRawQuery() != null && !target.getRawQuery().isEmpty()) {
        path += "?" + target.getRawQuery();
      }

      try (Socket upstream = new Socket(target.getHost(), port)) {
        OutputStream upstreamOut = upstream.getOutputStream();
        upstreamOut.write((parts[0] + " " + path + " HTTP/1.1\r\n").getBytes(StandardCharsets.US_ASCII));
        for (Map.Entry<String, String> header : headers.entrySet()) {
          upstreamOut.write(
              (header.getKey() + ": " + header.getValue() + "\r\n")
                  .getBytes(StandardCharsets.US_ASCII));
        }
        upstreamOut.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        upstreamOut.flush();

        transfer(upstream, clientOut);
      }
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

    private static void transfer(Socket source, OutputStream destination) {
      try {
        InputStream inputStream = source.getInputStream();
        byte[] buffer = new byte[8 * 1024];
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
          destination.write(buffer, 0, read);
          destination.flush();
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
      return connectHeaderFrom(headers, name);
    }

    private static String connectHeaderFrom(Map<String, String> headers, String name) {
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

  private enum ClientImplementation {
    NETTY("netty"),
    APACHE_ASYNC_HTTP_CLIENT5("apache-async-http-client5");

    private final String id;

    ClientImplementation(String id) {
      this.id = id;
    }

    private static ClientImplementation fromParameter(String value) {
      for (ClientImplementation implementation : values()) {
        if (implementation.id.equals(value)) {
          return implementation;
        }
      }
      throw new IllegalArgumentException(
          "Unsupported client implementation: " + value + ". Expected one of: netty, apache-async-http-client5");
    }
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
      try {
        ((HttpClientFacadeContractTest) context.getRequiredTestInstance())
            .setImplementation(ClientImplementation.fromParameter(implementationValue));
      } catch (IllegalArgumentException e) {
        throw new TestAbortedException(
            "Skipping contract test: unsupported implementation '" + implementationValue + "'", e);
      }
    }
  }
}

package datadog.openfeature.internal.http;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class Java11TransportTest {

  private HttpServer server;

  @AfterEach
  void closeServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void sendsHeadersAndReadsResponse() throws Exception {
    final AtomicReference<String> apiKey = new AtomicReference<>();
    final AtomicReference<String> etag = new AtomicReference<>();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/config",
        exchange -> {
          apiKey.set(exchange.getRequestHeaders().getFirst("DD-API-KEY"));
          etag.set(exchange.getRequestHeaders().getFirst("If-None-Match"));
          final byte[] body = "response".getBytes(UTF_8);
          exchange.getResponseHeaders().add("ETag", "\"next\"");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    final CdnConfigurationSource.Java11Transport transport =
        new CdnConfigurationSource.Java11Transport(HttpClient.newHttpClient());

    final CdnConfigurationSource.TransportResponse response =
        transport.fetch(options(Duration.ofSeconds(1), true), Map.of("If-None-Match", "\"old\""));

    assertEquals(200, response.status);
    assertEquals("\"next\"", response.etag);
    assertArrayEquals("response".getBytes(UTF_8), response.body);
    assertEquals("secret", apiKey.get());
    assertEquals("\"old\"", etag.get());
  }

  @Test
  void enforcesRequestTimeout() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/config",
        exchange -> {
          try {
            Thread.sleep(250);
            exchange.sendResponseHeaders(304, -1);
          } catch (final InterruptedException error) {
            Thread.currentThread().interrupt();
          } finally {
            exchange.close();
          }
        });
    server.start();
    final CdnConfigurationSource.Java11Transport transport =
        new CdnConfigurationSource.Java11Transport(HttpClient.newHttpClient());

    assertThrows(
        IOException.class, () -> transport.fetch(options(Duration.ofMillis(25), false), Map.of()));
  }

  @Test
  void requestsAndDecodesGzipResponses() throws Exception {
    final AtomicReference<String> acceptEncoding = new AtomicReference<>();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/config",
        exchange -> {
          acceptEncoding.set(exchange.getRequestHeaders().getFirst("Accept-Encoding"));
          final byte[] body = gzip("compressed".getBytes(UTF_8));
          exchange.getResponseHeaders().add("Content-Encoding", "gzip");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    final CdnConfigurationSource.Java11Transport transport =
        new CdnConfigurationSource.Java11Transport(HttpClient.newHttpClient());

    final CdnConfigurationSource.TransportResponse response =
        transport.fetch(options(Duration.ofSeconds(1), false), Map.of());

    assertEquals("gzip", acceptEncoding.get());
    assertArrayEquals("compressed".getBytes(UTF_8), response.body);
  }

  @Test
  void acceptsEmptyGzipNotModifiedResponses() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/config",
        exchange -> {
          exchange.getResponseHeaders().add("Content-Encoding", "gzip");
          exchange.sendResponseHeaders(304, -1);
          exchange.close();
        });
    server.start();
    final CdnConfigurationSource.Java11Transport transport =
        new CdnConfigurationSource.Java11Transport(HttpClient.newHttpClient());

    final CdnConfigurationSource.TransportResponse response =
        transport.fetch(options(Duration.ofSeconds(1), false), Map.of());

    assertEquals(304, response.status);
    assertArrayEquals(new byte[0], response.body);
  }

  @Test
  void cancelsRequestsBeforeAndDuringFetch() throws Exception {
    final CdnConfigurationSource.Java11Transport closed =
        new CdnConfigurationSource.Java11Transport(HttpClient.newHttpClient());
    closed.cancel();
    assertThrows(
        InterruptedIOException.class,
        () -> closed.fetch(options(Duration.ofSeconds(1), false), Map.of()));

    final CountDownLatch entered = new CountDownLatch(1);
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/config",
        exchange -> {
          entered.countDown();
          try {
            Thread.sleep(5_000);
          } catch (final InterruptedException error) {
            Thread.currentThread().interrupt();
          } finally {
            exchange.close();
          }
        });
    server.start();
    final CdnConfigurationSource.Java11Transport active =
        new CdnConfigurationSource.Java11Transport(HttpClient.newHttpClient());
    final AtomicReference<Throwable> failure = new AtomicReference<>();
    final Thread thread =
        new Thread(
            () -> {
              try {
                active.fetch(options(Duration.ofSeconds(10), false), Map.of());
              } catch (final Throwable error) {
                failure.set(error);
              }
            });
    thread.start();
    assertTrue(entered.await(1, TimeUnit.SECONDS));
    active.cancel();
    thread.join(1_000);

    assertTrue(failure.get() instanceof InterruptedIOException, String.valueOf(failure.get()));
  }

  private HttpConfigurationOptions options(
      final Duration requestTimeout, final boolean managedEndpoint) {
    final URI endpoint =
        server == null
            ? URI.create("https://example.test/config")
            : URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/config");
    return HttpConfigurationOptions.builder()
        .endpoint(endpoint)
        .requestTimeout(requestTimeout)
        .pollInterval(Duration.ofSeconds(30))
        .apiKey("secret")
        .managedEndpoint(managedEndpoint)
        .build();
  }

  private static byte[] gzip(final byte[] input) throws IOException {
    final ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
      gzip.write(input);
    }
    return output.toByteArray();
  }
}

package datadog.openfeature.internal.http;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class Java11TransportTest {

  private HttpServer server;
  private final List<CdnConfigurationSource.Java11Transport> transports = new ArrayList<>();

  @AfterEach
  void close() {
    transports.forEach(CdnConfigurationSource.Java11Transport::cancel);
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
    final CdnConfigurationSource.Java11Transport transport = newTransport();

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
    final CdnConfigurationSource.Java11Transport transport = newTransport();

    assertThrows(
        IOException.class, () -> transport.fetch(options(Duration.ofMillis(25), false), Map.of()));
  }

  @Test
  void unwrapsNestedCompletionFailures() {
    final IOException cause = new IOException("failure");

    assertSame(
        cause,
        CdnConfigurationSource.Java11Transport.unwrapCompletionFailure(
            new CompletionException(new CompletionException(cause))));
    final CompletionException withoutCause = new CompletionException((Throwable) null);
    assertSame(
        withoutCause, CdnConfigurationSource.Java11Transport.unwrapCompletionFailure(withoutCause));
  }

  @Test
  void omitsManagedEndpointHeaderWithoutANonEmptyApiKey() throws Exception {
    final List<String> apiKeys = new ArrayList<>();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/config",
        exchange -> {
          apiKeys.add(exchange.getRequestHeaders().getFirst("DD-API-KEY"));
          exchange.sendResponseHeaders(304, -1);
          exchange.close();
        });
    server.start();
    final CdnConfigurationSource.Java11Transport transport = newTransport();

    transport.fetch(options(Duration.ofSeconds(1), true, null), Map.of());
    transport.fetch(options(Duration.ofSeconds(1), true, ""), Map.of());

    assertEquals(Arrays.asList(null, null), apiKeys);
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
    final CdnConfigurationSource.Java11Transport transport = newTransport();

    final CdnConfigurationSource.TransportResponse response =
        transport.fetch(options(Duration.ofSeconds(1), false), Map.of());

    assertEquals("gzip", acceptEncoding.get());
    assertArrayEquals("compressed".getBytes(UTF_8), response.body);
  }

  @Test
  void cancelsRequestsBeforeAndDuringFetch() throws Exception {
    final CdnConfigurationSource.Java11Transport closed = newTransport();
    closed.cancel();
    assertThrows(
        InterruptedIOException.class,
        () -> closed.fetch(options(Duration.ofSeconds(1), false), Map.of()));

    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/config",
        exchange -> {
          entered.countDown();
          try {
            release.await(5, TimeUnit.SECONDS);
          } catch (final InterruptedException error) {
            Thread.currentThread().interrupt();
          } finally {
            exchange.close();
          }
        });
    server.start();
    final CdnConfigurationSource.Java11Transport active = newTransport();
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
    release.countDown();

    assertTrue(failure.get() instanceof InterruptedIOException, String.valueOf(failure.get()));
  }

  @Test
  void preservesInterruptionWhileWaitingForTheResponse() throws Exception {
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/config",
        exchange -> {
          entered.countDown();
          try {
            release.await(5, TimeUnit.SECONDS);
          } catch (final InterruptedException error) {
            Thread.currentThread().interrupt();
          } finally {
            exchange.close();
          }
        });
    server.start();
    final CdnConfigurationSource.Java11Transport transport = newTransport();
    final AtomicReference<Throwable> failure = new AtomicReference<>();
    final AtomicReference<Boolean> interrupted = new AtomicReference<>();
    final Thread thread =
        new Thread(
            () -> {
              try {
                transport.fetch(options(Duration.ofSeconds(10), false), Map.of());
              } catch (final Throwable error) {
                failure.set(error);
              } finally {
                interrupted.set(Thread.currentThread().isInterrupted());
              }
            });
    thread.start();
    assertTrue(entered.await(1, TimeUnit.SECONDS));

    thread.interrupt();
    thread.join(1_000);
    release.countDown();

    assertTrue(failure.get() instanceof InterruptedIOException, String.valueOf(failure.get()));
    assertEquals(Boolean.TRUE, interrupted.get());
  }

  @Test
  void rejectsKnownResponseLengthAboveTheLimit() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/config",
        exchange -> {
          exchange.sendResponseHeaders(200, UfcResponseBodyReader.MAX_COMPRESSED_BYTES + 1L);
          try (OutputStream output = exchange.getResponseBody()) {
            // Flush the response headers before the bounded subscriber cancels the body.
            output.write(0);
          } catch (final IOException ignored) {
            // The bounded subscriber rejects the declared length before it reads the body.
          }
        });
    server.start();
    final CdnConfigurationSource.Java11Transport transport = newTransport();

    final IOException error =
        assertThrows(
            IOException.class,
            () -> transport.fetch(options(Duration.ofSeconds(1), false), Map.of()));

    assertTrue(error instanceof UfcResponseBodyReader.ResponseTooLargeException, error.toString());
  }

  @Test
  void rejectsChunkedResponseAboveTheLimit() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/config",
        exchange -> {
          exchange.sendResponseHeaders(200, 0);
          final byte[] chunk = new byte[8 << 10];
          long remaining = UfcResponseBodyReader.MAX_COMPRESSED_BYTES + 1L;
          try (OutputStream output = exchange.getResponseBody()) {
            while (remaining > 0) {
              final int count = (int) Math.min(chunk.length, remaining);
              output.write(chunk, 0, count);
              remaining -= count;
            }
          } catch (final IOException ignored) {
            // The bounded subscriber closes the response after it reaches the limit.
          } finally {
            exchange.close();
          }
        });
    server.start();
    final CdnConfigurationSource.Java11Transport transport = newTransport();

    final IOException error =
        assertThrows(
            IOException.class,
            () -> transport.fetch(options(Duration.ofSeconds(2), false), Map.of()));

    assertTrue(error.getMessage().contains("exceeds"), error.toString());
  }

  @Test
  void rejectsGzipExpansionAboveTheLimit() throws Exception {
    final byte[] expanded = new byte[UfcResponseBodyReader.MAX_DECOMPRESSED_BYTES + 1];
    final byte[] compressed = gzip(expanded);
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/config",
        exchange -> {
          exchange.getResponseHeaders().add("Content-Encoding", "gzip");
          exchange.sendResponseHeaders(200, compressed.length);
          exchange.getResponseBody().write(compressed);
          exchange.close();
        });
    server.start();
    final CdnConfigurationSource.Java11Transport transport = newTransport();

    final IOException error =
        assertThrows(
            IOException.class,
            () -> transport.fetch(options(Duration.ofSeconds(2), false), Map.of()));

    assertTrue(error.getMessage().contains("exceeds"), error.toString());
  }

  @Test
  void stopsTheOwnedHttpWorkerOnCancel() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/config",
        exchange -> {
          exchange.sendResponseHeaders(304, -1);
          exchange.close();
        });
    server.start();
    final int baseline = threadCount("dd-openfeature-cdn-http");
    final CdnConfigurationSource.Java11Transport transport = newTransport();

    transport.fetch(options(Duration.ofSeconds(1), false), Map.of());
    awaitThreadCount("dd-openfeature-cdn-http", baseline + 1);
    transport.cancel();
    awaitThreadCount("dd-openfeature-cdn-http", baseline);

    assertTrue(transport.isTerminated());
  }

  @Test
  void lazyTransportReusesOneClientAndRejectsFetchAfterCancel() throws Exception {
    final CdnConfigurationSource.LazyTransport closed = new CdnConfigurationSource.LazyTransport();
    closed.cancel();
    closed.cancel();
    assertThrows(
        InterruptedIOException.class,
        () -> closed.fetch(options(Duration.ofSeconds(1), false), Map.of()));

    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/config",
        exchange -> {
          exchange.sendResponseHeaders(304, -1);
          exchange.close();
        });
    server.start();
    final CdnConfigurationSource.LazyTransport transport =
        new CdnConfigurationSource.LazyTransport();

    try {
      assertEquals(304, transport.fetch(options(Duration.ofSeconds(1), false), Map.of()).status);
      assertEquals(304, transport.fetch(options(Duration.ofSeconds(1), false), Map.of()).status);
      transport.cancel();
      transport.cancel();

      assertThrows(
          InterruptedIOException.class,
          () -> transport.fetch(options(Duration.ofSeconds(1), false), Map.of()));
    } finally {
      transport.cancel();
    }
  }

  private CdnConfigurationSource.Java11Transport newTransport() {
    final CdnConfigurationSource.Java11Transport transport =
        new CdnConfigurationSource.Java11Transport();
    transports.add(transport);
    return transport;
  }

  private static void awaitThreadCount(final String name, final int expected)
      throws InterruptedException {
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (threadCount(name) != expected && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertEquals(expected, threadCount(name));
  }

  private static int threadCount(final String name) {
    int count = 0;
    for (final Thread thread : Thread.getAllStackTraces().keySet()) {
      if (thread.isAlive() && name.equals(thread.getName())) {
        count++;
      }
    }
    return count;
  }

  private HttpConfigurationOptions options(
      final Duration requestTimeout, final boolean managedEndpoint) {
    return options(requestTimeout, managedEndpoint, "secret");
  }

  private HttpConfigurationOptions options(
      final Duration requestTimeout, final boolean managedEndpoint, final String apiKey) {
    final URI endpoint =
        server == null
            ? URI.create("https://example.test/config")
            : URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/config");
    return HttpConfigurationOptions.builder()
        .endpoint(endpoint)
        .requestTimeout(requestTimeout)
        .pollInterval(Duration.ofSeconds(30))
        .apiKey(apiKey)
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

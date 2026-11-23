package datadog.remoteconfig;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadoghq.remoteconfig.poc.HttpReply;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.List;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link OkHttpTransport} bounds response bodies to 16 MiB on the stream: an
 * oversized body must fail the reply without being fully materialized, and a normal body must pass
 * through with status and headers.
 */
class OkHttpTransportTest {
  private HttpServer server;
  private OkHttpTransport transport;

  @BeforeEach
  @SuppressWarnings("unused")
  void setup() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.start();
    transport = new OkHttpTransport(new OkHttpClient());
  }

  @AfterEach
  @SuppressWarnings("unused")
  void teardown() {
    server.stop(0);
  }

  @FunctionalInterface
  private interface Responder {
    void respond(HttpExchange exchange) throws IOException;
  }

  private void respond(String path, Responder responder) {
    server.createContext(
        path,
        exchange -> {
          drain(exchange);
          try {
            responder.respond(exchange);
          } catch (IOException ignored) {
            // the client aborts at the size cap, breaking the pipe server-side; expected
          } finally {
            exchange.close();
          }
        });
  }

  private static void drain(HttpExchange exchange) throws IOException {
    byte[] sink = new byte[4096];
    for (int read; (read = exchange.getRequestBody().read(sink)) >= 0; ) {
      // drain only
    }
  }

  private String url(String path) {
    return "http://127.0.0.1:" + server.getAddress().getPort() + path;
  }

  private HttpReply send(String path) throws Exception {
    return transport.send("POST", url(path), noHeaders(), new byte[0]).get(30, SECONDS);
  }

  @Test
  void rejectsBodyLargerThanTheCapWithoutMaterializingIt() throws Exception {
    // chunked body with no declared length, 16 MiB + 1 actually sent: the size check
    // runs on the stream, so the failure comes from the cap, never a full read
    byte[] chunk = new byte[64 * 1024];
    respond(
        "/too-big-chunked",
        exchange -> {
          exchange.sendResponseHeaders(200, 0); // chunked transfer
          for (long written = 0; written <= 16L * 1024 * 1024; written += chunk.length) {
            int len = (int) Math.min(chunk.length, 16L * 1024 * 1024 + 1 - written);
            exchange.getResponseBody().write(chunk, 0, Math.max(len, 1));
          }
        });

    HttpReply reply = send("/too-big-chunked");
    assertEquals(0, reply.status);
    assertNull(reply.body);
    assertTrue(
        reply.error.contains("maximum bytes") || reply.error.contains("Response exceeds"),
        "unexpected error: " + reply.error);
  }

  @Test
  void rejectsOversizedContentLengthWithoutReadingTheBody() throws Exception {
    // fixed-length response declaring 16 MiB + 1: the declared length alone fails the
    // reply before any body byte is read
    long oversized = 16L * 1024 * 1024 + 1;
    byte[] chunk = new byte[64 * 1024];
    respond(
        "/too-big-fixed",
        exchange -> {
          exchange.sendResponseHeaders(200, oversized);
          for (long written = 0; written < oversized; written += chunk.length) {
            int len = (int) Math.min(chunk.length, oversized - written);
            exchange.getResponseBody().write(chunk, 0, len);
          }
        });

    HttpReply reply = send("/too-big-fixed");
    assertEquals(0, reply.status);
    assertTrue(
        reply.error.contains("Response exceeds 16 MiB") || reply.error.contains("maximum bytes"),
        "unexpected error: " + reply.error);
  }

  @Test
  void passesStatusHeadersAndBodyThrough() throws Exception {
    byte[] body = "{\"target_changes\": []}".getBytes(UTF_8);
    respond(
        "/config",
        exchange -> {
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
        });

    HttpReply reply = send("/config");
    assertEquals(200, reply.status);
    assertArrayEquals(body, reply.body);
    assertNull(reply.error);
    assertTrue(
        reply.headers.stream()
            .anyMatch(
                header -> "content-type".equalsIgnoreCase(header[0]) && header[1].contains("json")),
        "headers seen: "
            + reply.headers.stream()
                .map(h -> h[0] + "=" + h[1])
                .collect(java.util.stream.Collectors.joining(", ")));
  }

  @Test
  void readsAStreamedBodyWithUnknownLength() throws Exception {
    // chunked transfer: no content length, the body is still bounded and read correctly
    respond(
        "/chunked",
        exchange -> {
          exchange.sendResponseHeaders(200, 0);
          exchange.getResponseBody().write("partial".getBytes(UTF_8));
          exchange.getResponseBody().write("-body".getBytes(UTF_8));
        });

    HttpReply reply = send("/chunked");
    assertEquals(200, reply.status);
    assertArrayEquals("partial-body".getBytes(UTF_8), reply.body);
  }

  private static List<String[]> noHeaders() {
    return Collections.emptyList();
  }
}

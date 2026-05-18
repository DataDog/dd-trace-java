package server;

import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TraceMatcher.SORT_BY_START_TIME;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.api.DDSpanTypes;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Regression test for the vertx-web 3.x route-handler span lifecycle on the response.sendFile(...)
 * path.
 *
 * <p>HttpServerResponseImpl.doSendFile (vertx-core 3.x) only invokes bodyEndHandler after the file
 * is written; it never invokes endHandler. With only the endHandler registration (pre-fix), the
 * vertx.route-handler span never finishes on this path, the trace fails to flush, and assertTraces
 * times out. With the fallback addBodyEndHandler registration, the span finishes on every
 * response-end path.
 */
class RouteHandlerSendFileTest extends AbstractInstrumentationTest {

  private static Vertx vertx;
  private static HttpServer server;
  private static int port;
  private static Path payload;

  @BeforeAll
  static void startServer() throws Exception {
    payload = Files.createTempFile("vertx-sendfile-", ".txt");
    Files.write(payload, "vertx sendFile payload\n".getBytes(StandardCharsets.UTF_8));
    payload.toFile().deleteOnExit();

    try (ServerSocket socket = new ServerSocket(0)) {
      port = socket.getLocalPort();
    }

    vertx = Vertx.vertx();
    Router router = Router.router(vertx);
    router
        .route("/sendfile")
        .handler(ctx -> ctx.response().sendFile(payload.toAbsolutePath().toString()));

    CountDownLatch ready = new CountDownLatch(1);
    server =
        vertx
            .createHttpServer()
            .requestHandler(router::accept)
            .listen(
                port,
                result -> {
                  if (result.failed()) {
                    throw new RuntimeException("Failed to start Vert.x server", result.cause());
                  }
                  ready.countDown();
                });
    if (!ready.await(10, TimeUnit.SECONDS)) {
      throw new IllegalStateException("Vert.x server did not start in time");
    }
  }

  @AfterAll
  static void stopServer() throws Exception {
    if (server != null) {
      CountDownLatch closed = new CountDownLatch(1);
      server.close(ar -> closed.countDown());
      closed.await(10, TimeUnit.SECONDS);
    }
    if (vertx != null) {
      CountDownLatch closed = new CountDownLatch(1);
      vertx.close(ar -> closed.countDown());
      closed.await(10, TimeUnit.SECONDS);
    }
    if (payload != null) {
      Files.deleteIfExists(payload);
    }
  }

  @Test
  void sendFileFinishesRouteHandlerSpan() throws Exception {
    HttpURLConnection conn =
        (HttpURLConnection) new URL("http://localhost:" + port + "/sendfile").openConnection();
    conn.setRequestMethod("GET");
    conn.setConnectTimeout(5000);
    conn.setReadTimeout(5000);
    assertEquals(200, conn.getResponseCode());
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
      assertEquals("vertx sendFile payload", reader.readLine());
    }

    // Pre-fix: the route-handler span never finishes on the sendFile path, so the trace
    // is never published and assertTraces times out waiting for the trace to flush.
    assertTraces(
        trace(
            SORT_BY_START_TIME,
            span()
                .operationName(Pattern.compile(Pattern.quote("netty.request")))
                .type(DDSpanTypes.HTTP_SERVER),
            span()
                .childOfPrevious()
                .operationName(Pattern.compile(Pattern.quote("vertx.route-handler")))
                .type(DDSpanTypes.HTTP_SERVER)));
  }
}

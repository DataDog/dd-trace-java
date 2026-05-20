package server;

import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TraceMatcher.SORT_BY_START_TIME;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.api.DDSpanTypes;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.impl.ResponseExceptionFiringHelper;
import io.vertx.ext.web.Router;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Regression test for the vertx-web 3.x route-handler span lifecycle on the
 * response.exceptionHandler path.
 *
 * <p>HttpServerResponseImpl.handleException is invoked by Vert.x on non-CLOSED_EXCEPTION I/O
 * failures of the response. Neither endHandler nor bodyEndHandler fires on this path, so the
 * route-handler span would leak without an exception handler registered. The route handler here
 * fires handleException directly via ResponseExceptionFiringHelper (the package-private method
 * Vert.x itself uses internally), then calls response.end() normally so the HTTP client gets a
 * response.
 */
class RouteHandlerExceptionHandlerTest extends AbstractInstrumentationTest {

  private static Vertx vertx;
  private static HttpServer server;
  private static int port;

  @BeforeAll
  static void startServer() throws Exception {
    try (ServerSocket socket = new ServerSocket(0)) {
      port = socket.getLocalPort();
    }

    vertx = Vertx.vertx();
    Router router = Router.router(vertx);
    router
        .route("/fail")
        .handler(
            ctx -> {
              ResponseExceptionFiringHelper.fireException(
                  ctx.response(), new IOException("simulated response I/O failure"));
              try {
                ctx.response().setStatusCode(500).end("error");
              } catch (IllegalStateException ignore) {
                // handleException may have left the response in a state where end() is rejected;
                // the span is already finished by our registered exception handler.
              }
            });

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
  }

  @Test
  void exceptionHandlerFinishesRouteHandlerSpan() throws Exception {
    HttpURLConnection conn =
        (HttpURLConnection) new URL("http://localhost:" + port + "/fail").openConnection();
    conn.setRequestMethod("GET");
    conn.setConnectTimeout(5000);
    conn.setReadTimeout(5000);
    try {
      // We don't care about the response code or body — only that the trace flushes.
      conn.getResponseCode();
    } catch (IOException ignore) {
      // If end() was rejected after handleException, the client may see a closed connection.
    } finally {
      conn.disconnect();
    }

    // The netty.request span is marked as errored because the route handler ends with
    // HTTP 500; the route-handler span is finished by our exception handler before
    // setStatusCode(500), so it sees status=200 (default) and is not errored.
    assertTraces(
        trace(
            SORT_BY_START_TIME,
            span()
                .operationName(Pattern.compile(Pattern.quote("netty.request")))
                .type(DDSpanTypes.HTTP_SERVER)
                .error(),
            span()
                .childOfPrevious()
                .operationName(Pattern.compile(Pattern.quote("vertx.route-handler")))
                .type(DDSpanTypes.HTTP_SERVER)));
  }
}

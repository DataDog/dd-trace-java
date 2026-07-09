package server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.HttpVersion;
import io.vertx.ext.web.Router;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class HttpServerResponseEndHandlerInstrumentationTest extends AbstractInstrumentationTest {
  private static final String HTTP1_RESPONSE = "io.vertx.core.http.impl.http1.Http1ServerResponse";
  private static final String HTTP2_RESPONSE = "io.vertx.core.http.impl.HttpServerResponseImpl";
  private static final String END_HANDLER_WRAPPER =
      "datadog.trace.instrumentation.vertx_4_0.server.EndHandlerWrapper";

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
        .route("/end-handler-wrapper")
        .handler(HttpServerResponseEndHandlerInstrumentationTest::handle);

    CountDownLatch ready = new CountDownLatch(1);
    vertx
        .createHttpServer(new HttpServerOptions().setHttp2ClearTextEnabled(true))
        .requestHandler(router)
        .listen(port)
        .andThen(
            result -> {
              if (result.failed()) {
                throw new RuntimeException("Failed to start Vert.x server", result.cause());
              }
              server = result.result();
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
      server.close().andThen(ar -> closed.countDown());
      closed.await(10, TimeUnit.SECONDS);
    }
    if (vertx != null) {
      CountDownLatch closed = new CountDownLatch(1);
      vertx.close().andThen(ar -> closed.countDown());
      closed.await(10, TimeUnit.SECONDS);
    }
  }

  @Test
  void wrapsApplicationEndHandlerOnVertx51Http1Response() throws Exception {
    assumeTrue(hasVertx51Http1Response(), "Http1ServerResponse exists only in Vert.x 5.1+");

    HttpURLConnection conn =
        (HttpURLConnection)
            new URL("http://localhost:" + port + "/end-handler-wrapper").openConnection();
    conn.setRequestMethod("GET");
    conn.setConnectTimeout(5000);
    conn.setReadTimeout(5000);

    int responseCode = conn.getResponseCode();
    InputStream responseStream =
        responseCode >= 400 ? conn.getErrorStream() : conn.getInputStream();
    String body = new String(responseStream.readAllBytes(), StandardCharsets.UTF_8);
    conn.disconnect();

    assertWrapped(responseCode, body, HTTP1_RESPONSE);
  }

  @Test
  void wrapsApplicationEndHandlerOnVertx51Http2Response() throws Exception {
    assumeTrue(hasVertx51Http2Response(), "HttpServerResponseImpl exists only in Vert.x 5.1+");

    HttpClient client =
        vertx.createHttpClient(
            new HttpClientOptions()
                .setProtocolVersion(HttpVersion.HTTP_2)
                .setHttp2ClearTextUpgrade(false));
    try {
      HttpClientResponse response =
          client
              .request(HttpMethod.GET, port, "localhost", "/end-handler-wrapper")
              .compose(request -> request.send())
              .toCompletionStage()
              .toCompletableFuture()
              .get(10, TimeUnit.SECONDS);
      Buffer body =
          response.body().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

      assertWrapped(response.statusCode(), body.toString(StandardCharsets.UTF_8), HTTP2_RESPONSE);
    } finally {
      client.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
  }

  private static boolean hasVertx51Http1Response() {
    return hasClass(HTTP1_RESPONSE);
  }

  private static boolean hasVertx51Http2Response() {
    return hasClass(HTTP2_RESPONSE);
  }

  private static boolean hasClass(String className) {
    try {
      Class.forName(className);
      return true;
    } catch (ClassNotFoundException ignored) {
      return false;
    }
  }

  private static void assertWrapped(int responseCode, String body, String expectedResponseClass) {
    assertEquals(200, responseCode, body);
    assertTrue(body.contains("response=" + expectedResponseClass), body);
    assertTrue(body.contains("endHandler=" + END_HANDLER_WRAPPER), body);
  }

  private static void handle(io.vertx.ext.web.RoutingContext ctx) {
    HttpServerResponse response = ctx.response();
    Handler<Void> applicationEndHandler = ignored -> {};
    response.endHandler(applicationEndHandler);

    String responseClassName = response.getClass().getName();
    Object installedEndHandler;
    try {
      Field endHandler = response.getClass().getDeclaredField("endHandler");
      endHandler.setAccessible(true);
      installedEndHandler = endHandler.get(response);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError("Could not inspect Vert.x response endHandler field", e);
    }

    String installedClassName =
        installedEndHandler == null ? "<null>" : installedEndHandler.getClass().getName();
    boolean wrapped =
        (HTTP1_RESPONSE.equals(responseClassName) || HTTP2_RESPONSE.equals(responseClassName))
            && END_HANDLER_WRAPPER.equals(installedClassName);
    response
        .setStatusCode(wrapped ? 200 : 500)
        .end("response=" + responseClassName + "\nendHandler=" + installedClassName + "\n");
  }
}

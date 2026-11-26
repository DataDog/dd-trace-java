package server.normalizedroute;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.agent.test.utils.OkHttpUtils;
import datadog.trace.bootstrap.ActiveSubsystems;
import datadog.trace.core.DDSpan;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import java.net.ServerSocket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;

/**
 * Runs a Vert.x 3.x server with the normalized route test routes, with AppSec forced active like
 * the Spock {@code InstrumentationSpecification}, and returns the tags of the server span.
 */
@TestInstance(PER_CLASS)
abstract class AbstractVertxNormalizedRouteTest extends AbstractInstrumentationTest {

  private final OkHttpClient client = OkHttpUtils.client();
  private Vertx vertx;
  private HttpServer server;
  private int port;
  private boolean appSecActiveBefore;

  @BeforeAll
  void startServer() throws Exception {
    try (ServerSocket socket = new ServerSocket(0)) {
      port = socket.getLocalPort();
    }
    vertx = Vertx.vertx();
    Router router = Router.router(vertx);
    router.route("/").handler(AbstractVertxNormalizedRouteTest::ok);
    router.route("/multi/:id.:format").handler(AbstractVertxNormalizedRouteTest::ok);
    router.route("/three/:a-:b-:c").handler(AbstractVertxNormalizedRouteTest::ok);
    router.route("/static-text/user-:id").handler(AbstractVertxNormalizedRouteTest::ok);
    router.route("/catch-all/*").handler(AbstractVertxNormalizedRouteTest::ok);
    router.route("/encoded/caf%C3%A9").handler(AbstractVertxNormalizedRouteTest::ok);
    router.routeWithRegex("/regex/(\\d+)").handler(AbstractVertxNormalizedRouteTest::ok);
    // middleware with a declared path, then a regex route: the regex route must not keep its tag
    router.route("/stale/*").handler(RoutingContext::next);
    router.routeWithRegex("/stale/(\\d+)").handler(AbstractVertxNormalizedRouteTest::ok);
    // same, but the regex route also has a path, so http.route is updated by the regex route
    router.route("/stale-path/*").handler(RoutingContext::next);
    router
        .route("/stale-path/x")
        .pathRegex("/stale-path/(\\d+)")
        .handler(AbstractVertxNormalizedRouteTest::ok);

    Router sub = Router.router(vertx);
    sub.route("/users/:id").handler(AbstractVertxNormalizedRouteTest::ok);
    sub.route("/files/*").handler(AbstractVertxNormalizedRouteTest::ok);
    router.mountSubRouter("/sub", sub);

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
  void stopServer() throws Exception {
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

  @BeforeEach
  void forceAppSecActive() {
    appSecActiveBefore = ActiveSubsystems.APPSEC_ACTIVE;
    ActiveSubsystems.APPSEC_ACTIVE = true;
  }

  @AfterEach
  void restoreAppSecActive() {
    ActiveSubsystems.APPSEC_ACTIVE = appSecActiveBefore;
  }

  private static void ok(RoutingContext ctx) {
    ctx.response().setStatusCode(200).end("ok");
  }

  /** Sends {@code GET path} and returns the HTTP status. */
  int get(String path) throws Exception {
    Request request = new Request.Builder().url("http://localhost:" + port + path).get().build();
    try (Response response = client.newCall(request).execute()) {
      return response.code();
    }
  }

  /** Tags of the {@code netty.request} span of the single trace written. */
  Map<String, Object> serverSpanTags() throws Exception {
    writer.waitForTraces(1);
    DDSpan serverSpan = null;
    for (List<DDSpan> trace : writer) {
      for (DDSpan span : trace) {
        if ("netty.request".equals(span.getOperationName().toString())) {
          serverSpan = span;
        }
      }
    }
    assertNotNull(serverSpan, "netty.request span");
    return serverSpan.getTags();
  }
}

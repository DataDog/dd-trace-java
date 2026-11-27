package server.normalizedroute;

import static datadog.trace.api.appsec.route.NormalizedRouteHelper.NORMALIZED_ROUTE_TAG;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_ROUTE;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.agent.test.utils.OkHttpUtils;
import datadog.trace.bootstrap.ActiveSubsystems;
import datadog.trace.core.DDSpan;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;

/**
 * Runs a Vert.x 4.x or 5.x server with the normalized route test routes, with AppSec forced active
 * like the Spock {@code InstrumentationSpecification}, and returns the tags of the server span.
 * Only APIs shared by Vert.x 4.0.0 and 5.x are used, so the same source runs on every version.
 */
@TestInstance(PER_CLASS)
abstract class AbstractVertxNormalizedRouteTest extends AbstractInstrumentationTest {

  private static final long TIMEOUT_SECONDS = 10;

  /** Operation name of the server span, which carries both route tags. */
  private static final String SERVER_SPAN = "netty.request";

  private final OkHttpClient client = OkHttpUtils.client();
  Vertx vertx;
  private HttpServer server;
  private int port;
  private boolean appSecActiveBefore;

  @BeforeAll
  void startServer() throws Exception {
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
    // path() then pathRegex() on the same route: the setRegex advice drops the declared path
    router.route("/stale-path/*").handler(RoutingContext::next);
    router
        .route("/stale-path/x")
        .pathRegex("/stale-path/(\\d+)")
        .handler(AbstractVertxNormalizedRouteTest::ok);

    // what mountSubRouter("/sub/", sub) does on 4.x (mountSubRouter is gone in 5.x)
    Router sub = Router.router(vertx);
    sub.route("/users/:id").handler(AbstractVertxNormalizedRouteTest::ok);
    sub.route("/files/*").handler(AbstractVertxNormalizedRouteTest::ok);
    router.route("/sub/*").subRouter(sub);

    // a mount with a parameter: the mount point is the concrete matched prefix
    Router tenant = Router.router(vertx);
    tenant.route("/items/:id").handler(AbstractVertxNormalizedRouteTest::ok);
    router.route("/:tenant/api/*").subRouter(tenant);

    server = await(vertx.createHttpServer().requestHandler(router).listen(0));
    port = server.actualPort();
  }

  @AfterAll
  void stopServer() throws Exception {
    if (server != null) {
      await(server.close());
    }
    if (vertx != null) {
      await(vertx.close());
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

  private static <T> T await(Future<T> future) throws Exception {
    CountDownLatch done = new CountDownLatch(1);
    AtomicReference<T> result = new AtomicReference<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    future.onComplete(ar -> {
      if (ar.succeeded()) {
        result.set(ar.result());
      } else {
        failure.set(ar.cause());
      }
      done.countDown();
    });
    if (!done.await(TIMEOUT_SECONDS, SECONDS)) {
      throw new IllegalStateException("Vert.x operation did not complete in time");
    }
    if (failure.get() != null) {
      throw new RuntimeException("Vert.x operation failed", failure.get());
    }
    return result.get();
  }

  /** Sends {@code GET path} and returns the HTTP status. */
  int get(String path) throws Exception {
    Request request = new Request.Builder().url("http://localhost:" + port + path).get().build();
    try (Response response = client.newCall(request).execute()) {
      return response.code();
    }
  }

  /** Tags of the {@link #SERVER_SPAN} span of the single trace written. */
  Map<String, Object> serverSpanTags() throws Exception {
    writer.waitForTraces(1);
    DDSpan serverSpan = null;
    for (List<DDSpan> trace : writer) {
      for (DDSpan span : trace) {
        if (SERVER_SPAN.equals(span.getOperationName().toString())) {
          serverSpan = span;
        }
      }
    }
    assertNotNull(serverSpan, SERVER_SPAN + " span");
    return serverSpan.getTags();
  }

  /**
   * Sends {@code GET request}, expects a 200 with {@code expectedHttpRoute} (null for none) as
   * {@code http.route} and no normalized route.
   */
  void assertNoNormalizedRoute(String request, String expectedHttpRoute) throws Exception {
    assertEquals(200, get(request));
    Map<String, Object> tags = serverSpanTags();

    assertEquals(expectedHttpRoute, Objects.toString(tags.get(HTTP_ROUTE), null));
    assertNull(tags.get(NORMALIZED_ROUTE_TAG));
  }
}

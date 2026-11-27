package server.normalizedroute;

import static datadog.trace.api.appsec.route.NormalizedRouteHelper.NORMALIZED_ROUTE_TAG;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_ROUTE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import datadog.trace.bootstrap.ActiveSubsystems;
import io.vertx.ext.web.Router;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

/** Normalized route of Vert.x 4.x and 5.x routes, top level and under the {@code /sub} mount. */
class VertxNormalizedRouteTest extends AbstractVertxNormalizedRouteTest {

  @TableTest({
    "Scenario                          | Request                | HttpRoute               | Normalized              ",
    "root                              | '/'                    | '/'                     | '/'                     ",
    "two params in a segment           | '/multi/1.json'        | '/multi/:id.:format'    | '/multi/{id}.{format}'  ",
    "three params in a segment         | '/three/1-2-3'         | '/three/:a-:b-:c'       | '/three/{a}-{b}-{c}'    ",
    "static text before a param        | '/static-text/user-1'  | '/static-text/user-:id' | '/static-text/user-{id}'",
    // getPath() drops the trailing '*' (http.route '/catch-all/'), so '{param1}' proves the
    // declared '/catch-all/*' was captured from setPath
    "catch-all present                 | '/catch-all/a/b/c.txt' | '/catch-all/'           | '/catch-all/{param1}'   ",
    "catch-all matched a slash         | '/catch-all/'          | '/catch-all/'           | '/catch-all/'           ",
    "catch-all after a collapsed slash | '/catch-all//'         | '/catch-all/'           | '/catch-all/'           ",
    "encoded literal                   | '/encoded/caf%C3%A9'   | '/encoded/café'         | '/encoded/caf%C3%A9'    ",
    "sub-router param                  | '/sub/users/1'         | '/sub/users/:id'        | '/sub/users/{id}'       ",
    "sub-router catch-all              | '/sub/files/a'         | '/sub/files/'           | '/sub/files/{param1}'   "
  })
  void normalizedRoute(String request, String httpRoute, String normalized) throws Exception {
    assertEquals(200, get(request));
    Map<String, Object> tags = serverSpanTags();

    assertEquals(httpRoute, String.valueOf(tags.get(HTTP_ROUTE)));
    assertEquals(normalized, String.valueOf(tags.get(NORMALIZED_ROUTE_TAG)));
  }

  /** {@code http.route} falls back to the regex of the route; a regex has no normalized route. */
  @Test
  void noNormalizedRouteForRegexRoute() throws Exception {
    assertNoNormalizedRoute("/regex/123", "/regex/(\\d+)");
  }

  /**
   * The {@code /stale/*} middleware tags the span first; the regex route then updates {@code
   * http.route} with its regex and must clear the normalized route of the middleware.
   */
  @Test
  void staleNormalizedRouteIsClearedByLaterRegexRoute() throws Exception {
    assertNoNormalizedRoute("/stale/1", "/stale/(\\d+)");
  }

  /**
   * Under {@code route("/:tenant/api/*").subRouter(..)} the mount point is the concrete prefix
   * ({@code /acme/api/}), which holds a request value: no normalized route, {@code http.route}
   * unchanged.
   */
  @Test
  void declaredPathIsDroppedByALaterPathRegex() throws Exception {
    assertNoNormalizedRoute("/stale-path/1", "/stale-path/x");
  }

  /**
   * A parameter mount can never reuse a child param name: Vert.x rejects the repeated identifier
   * when mounting and when a route is added later, so the mount-param check always sees a foreign
   * key.
   */
  @Test
  void vertxRejectsAParameterMountReusingAChildParamName() {
    Router child = Router.router(vertx);
    child.route("/items/:id").handler(ctx -> ctx.response().end());
    assertThrows(
        RuntimeException.class, () -> Router.router(vertx).route("/:id/api/*").subRouter(child));

    Router laterChild = Router.router(vertx);
    Router.router(vertx).route("/:id/api/*").subRouter(laterChild);
    assertThrows(
        RuntimeException.class,
        () -> laterChild.route("/items/:id").handler(ctx -> ctx.response().end()));
  }

  @Test
  void noNormalizedRouteUnderAParameterMount() throws Exception {
    assertNoNormalizedRoute("/acme/api/items/1", "/acme/api/items/:id");
  }

  @Test
  void noNormalizedRouteWhenAppSecIsInactive() throws Exception {
    ActiveSubsystems.APPSEC_ACTIVE = false;

    assertNoNormalizedRoute("/multi/1.json", "/multi/:id.:format");
  }

  /**
   * {@code /catch-all} for {@code /catch-all/*}: Vert.x 4.0.3+ matches it (catch-all removed),
   * 4.0.0 to 4.0.2 do not (404, no route handler, no tag).
   */
  @Test
  void catchAllWithoutTrailingSlash() throws Exception {
    int status = get("/catch-all");
    Map<String, Object> tags = serverSpanTags();

    if (status == 200) {
      assertEquals("/catch-all/", String.valueOf(tags.get(HTTP_ROUTE)));
      assertEquals("/catch-all", String.valueOf(tags.get(NORMALIZED_ROUTE_TAG)));
    } else {
      assertEquals(404, status);
      assertNull(tags.get(HTTP_ROUTE));
      assertNull(tags.get(NORMALIZED_ROUTE_TAG));
    }
  }
}

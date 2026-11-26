package server.normalizedroute;

import static datadog.trace.api.appsec.route.NormalizedRouteHelper.NORMALIZED_ROUTE_TAG;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_ROUTE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.trace.bootstrap.ActiveSubsystems;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

/** Normalized route of Vert.x 4.x and 5.x routes, top level and under the {@code /sub} mount. */
class VertxNormalizedRouteTest extends AbstractVertxNormalizedRouteTest {

  @TableTest({
    "Scenario                          | Request                | Route                   | Normalized              ",
    "root                              | '/'                    | '/'                     | '/'                     ",
    "two params in a segment           | '/multi/1.json'        | '/multi/:id.:format'    | '/multi/{id}.{format}'  ",
    "three params in a segment         | '/three/1-2-3'         | '/three/:a-:b-:c'       | '/three/{a}-{b}-{c}'    ",
    "static text before a param        | '/static-text/user-1'  | '/static-text/user-:id' | '/static-text/user-{id}'",
    "catch-all present                 | '/catch-all/a/b/c.txt' | '/catch-all/'           | '/catch-all/{param1}'   ",
    "catch-all matched a slash         | '/catch-all/'          | '/catch-all/'           | '/catch-all/'           ",
    "catch-all after a collapsed slash | '/catch-all//'         | '/catch-all/'           | '/catch-all/'           ",
    "encoded literal                   | '/encoded/caf%C3%A9'   | '/encoded/café'         | '/encoded/caf%C3%A9'    ",
    "sub-router param                  | '/sub/users/1'         | '/sub/users/:id'        | '/sub/users/{id}'       ",
    "sub-router catch-all              | '/sub/files/a'         | '/sub/files/'           | '/sub/files/{param1}'   "
  })
  void normalizedRoute(String request, String route, String normalized) throws Exception {
    assertEquals(200, get(request));
    Map<String, Object> tags = serverSpanTags();

    assertEquals(route, String.valueOf(tags.get(HTTP_ROUTE)));
    assertEquals(normalized, String.valueOf(tags.get(NORMALIZED_ROUTE_TAG)));
  }

  /**
   * The store holds the declared {@code /catch-all/*}: {@code getPath()} drops the trailing {@code
   * *} (http.route {@code /catch-all/}), so only the declared path gives {@code {param1}}.
   */
  @Test
  void storeKeepsTheTrailingStarOfTheDeclaredPath() throws Exception {
    assertEquals(200, get("/catch-all/x"));
    Map<String, Object> tags = serverSpanTags();

    assertEquals("/catch-all/", String.valueOf(tags.get(HTTP_ROUTE)));
    assertEquals("/catch-all/{param1}", String.valueOf(tags.get(NORMALIZED_ROUTE_TAG)));
  }

  /** {@code http.route} falls back to the regex of the route; a regex has no normalized route. */
  @Test
  void noNormalizedRouteForRegexRoute() throws Exception {
    assertEquals(200, get("/regex/123"));
    Map<String, Object> tags = serverSpanTags();

    assertEquals("/regex/(\\d+)", String.valueOf(tags.get(HTTP_ROUTE)));
    assertNull(tags.get(NORMALIZED_ROUTE_TAG));
  }

  /**
   * The {@code /stale/*} middleware tags the span first; the regex route then updates {@code
   * http.route} with its regex and must clear the normalized route of the middleware.
   */
  @Test
  void staleNormalizedRouteIsClearedByLaterRegexRoute() throws Exception {
    assertEquals(200, get("/stale/1"));
    Map<String, Object> tags = serverSpanTags();

    assertEquals("/stale/(\\d+)", String.valueOf(tags.get(HTTP_ROUTE)));
    assertNull(tags.get(NORMALIZED_ROUTE_TAG));
  }

  /**
   * Under {@code route("/:tenant/api/*").subRouter(..)} the mount point is the concrete prefix
   * ({@code /acme/api/}), which holds a request value: no normalized route, {@code http.route}
   * unchanged.
   */
  @Test
  void noNormalizedRouteUnderAParameterMount() throws Exception {
    assertEquals(200, get("/acme/api/items/1"));
    Map<String, Object> tags = serverSpanTags();

    assertEquals("/acme/api/items/:id", String.valueOf(tags.get(HTTP_ROUTE)));
    assertNull(tags.get(NORMALIZED_ROUTE_TAG));
  }

  @Test
  void noNormalizedRouteWhenAppSecIsInactive() throws Exception {
    ActiveSubsystems.APPSEC_ACTIVE = false;

    assertEquals(200, get("/multi/1.json"));
    Map<String, Object> tags = serverSpanTags();

    assertEquals("/multi/:id.:format", String.valueOf(tags.get(HTTP_ROUTE)));
    assertNull(tags.get(NORMALIZED_ROUTE_TAG));
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

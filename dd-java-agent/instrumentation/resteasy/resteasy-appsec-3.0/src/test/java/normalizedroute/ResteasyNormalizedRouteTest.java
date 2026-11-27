package normalizedroute;

import static datadog.trace.api.appsec.route.NormalizedRouteHelper.NORMALIZED_ROUTE_TAG;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_ROUTE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.trace.bootstrap.ActiveSubsystems;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.tabletest.junit.TableTest;

/**
 * End-to-end normalized route for RESTEasy 3 served by a Jetty servlet container: the RESTEasy
 * capture advice provides the mount prefix ({@code /ctx} context path plus the {@code /api} mapping
 * prefix) and the bound values, the JAX-RS annotations decorator sets the tag on the server span.
 */
class ResteasyNormalizedRouteTest extends AbstractResteasyNormalizedRouteTest {

  @TableTest({
    "Scenario                         | Path                   | Route                              | Normalized                          ",
    "param and static text in segment | '/files/report.json'   | '/files/{name}.json'               | '/files/{name}.json'                ",
    "several params in one segment    | '/three/1-2-3'         | '/three/{a}-{b}-{c}'               | '/three/{a}-{b}-{c}'                ",
    "static prefix in segment         | '/users/user-42'       | '/users/user-{id}'                 | '/users/user-{id}'                  ",
    "regex constraint removed         | '/regex/123'           | '/regex/{id: [0-9]+}'              | '/regex/{id}'                       ",
    "catch-all with slashes           | '/catch-all/a/b/c.txt' | '/catch-all/{path: .*}'            | '/catch-all/{path}'                 ",
    "param names sanitized and unique | '/param-names/a/b'     | '/param-names/{user-id}/{user_id}' | '/param-names/{user_id_2}/{user_id}'",
    "non-ASCII literal encoded        | '/encoded/caf%C3%A9'   | '/encoded/café'                    | '/encoded/caf%C3%A9'                "
  })
  void normalizedRoute(String path, String route, String normalized) throws Exception {
    Map<String, Object> tags = serverSpanTags(path);

    assertEquals(route, String.valueOf(tags.get(HTTP_ROUTE)));
    assertEquals(PREFIX + normalized, String.valueOf(tags.get(NORMALIZED_ROUTE_TAG)));
  }

  /**
   * RESTEasy 3 does not match {@code {path: .*}} with an empty or only-{@code /} value (404), so
   * there is no route and no normalized route.
   */
  @ParameterizedTest
  @ValueSource(strings = {"/catch-all/", "/catch-all//"})
  void catchAllWithEmptyValueIsNotMatched(String path) throws Exception {
    Map<String, Object> tags = serverSpanTags(path, 404);

    assertNull(tags.get(HTTP_ROUTE));
    assertNull(tags.get(NORMALIZED_ROUTE_TAG));
  }

  @Test
  void noNormalizedRouteWhenAppSecIsInactive() throws Exception {
    ActiveSubsystems.APPSEC_ACTIVE = false;

    Map<String, Object> tags = serverSpanTags("/regex/123");

    assertEquals("/regex/{id: [0-9]+}", String.valueOf(tags.get(HTTP_ROUTE)));
    assertNull(tags.get(NORMALIZED_ROUTE_TAG));
  }

  /**
   * RESTEasy invokes a sub-resource locator before the sub-resource method it returns: the
   * locator's truncated route keeps http.route as before and sets no normalized route.
   */
  @Test
  void noNormalizedRouteForASubResourceLocator() throws Exception {
    Map<String, Object> tags = serverSpanTags("/locator/1/x");

    assertEquals("/locator/{id}", String.valueOf(tags.get(HTTP_ROUTE)));
    assertNull(tags.get(NORMALIZED_ROUTE_TAG));
  }
}

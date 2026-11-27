package datadog.trace.instrumentation.springweb6.normalizedroute;

import static datadog.trace.api.appsec.route.NormalizedRouteHelper.NORMALIZED_ROUTE_TAG;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_ROUTE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.trace.bootstrap.ActiveSubsystems;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

/**
 * Normalized route for PathPatternParser routes. Non-root context path, DispatcherServlet mapped to
 * {@code /}.
 */
class NormalizedRouteTest extends AbstractNormalizedRouteTest {

  @TableTest({
    "Scenario                                 | Path           | Route                     | Normalized            ",
    "regex constraint                         | '/regex/123'   | '/regex/{id:\\d+}'        | '/regex/{id}'         ",
    "regex with braces                        | '/nested/123'  | '/nested/{code:[0-9]{3}}' | '/nested/{code}'      ",
    "single star                              | '/files/a.txt' | '/files/*.txt'            | '/files/{param1}.txt' ",
    "trailing double star                     | '/static/a/b'  | '/static/**'              | '/static/{param1}'    ",
    "double star matched nothing: removed     | '/static'      | '/static/**'              | '/static'             ",
    "double star matched a slash: slash kept  | '/static/'     | '/static/**'              | '/static/'            ",
    "named and double star                    | '/mixed/1/x/y' | '/mixed/{id}/**'          | '/mixed/{id}/{param1}'",
    "named and empty double star              | '/mixed/1'     | '/mixed/{id}/**'          | '/mixed/{id}'         ",
    "named and double star matched a slash    | '/mixed/1/'    | '/mixed/{id}/**'          | '/mixed/{id}/'        ",
    "wildcard before double star: fallback    | '/star/a/b'    | '/star/*/**'              | '/star/{param1}'      ",
    "capture rest                             | '/rest/a/b'    | '/rest/{*rest}'           | '/rest/{rest}'        ",
    "capture rest matched nothing: removed    | '/rest'        | '/rest/{*rest}'           | '/rest'               ",
    "capture rest matched a slash: slash kept | '/rest/'       | '/rest/{*rest}'           | '/rest/'              "
  })
  void normalizedRoute(String path, String route, String normalized) throws Exception {
    Map<String, Object> tags = serverSpanTags(path);

    assertEquals(route, String.valueOf(tags.get(HTTP_ROUTE)));
    assertEquals(prefix() + normalized, String.valueOf(tags.get(NORMALIZED_ROUTE_TAG)));
  }

  @Test
  void noNormalizedRouteWhenAppSecIsInactive() throws Exception {
    ActiveSubsystems.APPSEC_ACTIVE = false;

    Map<String, Object> tags = serverSpanTags("/regex/123");

    assertEquals("/regex/{id:\\d+}", String.valueOf(tags.get(HTTP_ROUTE)));
    assertNull(tags.get(NORMALIZED_ROUTE_TAG));
  }
}

package dd.trace.instrumentation.springwebflux.normalizedroute;

import static datadog.trace.api.appsec.route.NormalizedRouteHelper.NORMALIZED_ROUTE_TAG;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_ROUTE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.trace.bootstrap.ActiveSubsystems;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

/**
 * Normalized route for WebFlux routes under {@code spring.webflux.base-path} (in the prefix).
 *
 * <p>Functional rows ({@code /fn}, {@code /fns}) are resolved like the annotated ones: since 5.2
 * {@code RouterFunctionMapping} sets {@code BEST_MATCHING_PATTERN_ATTRIBUTE}, so {@code
 * WebfluxNormalizedRoute.tagAnnotated} runs last with the matched pattern and Spring's bound
 * variables.
 */
class NormalizedRouteTest extends AbstractNormalizedRouteTest {

  @TableTest({
    "Scenario                                 | Path          | Route              | Normalized                 ",
    "regex constraint                         | '/regex/123'  | '/regex/{id:\\d+}' | '/base/regex/{id}'         ",
    "trailing double star                     | '/static/a/b' | '/static/**'       | '/base/static/{param1}'    ",
    "double star matched nothing: removed     | '/static'     | '/static/**'       | '/base/static'             ",
    "double star matched a slash: slash kept  | '/static/'    | '/static/**'       | '/base/static/'            ",
    "named and double star                    | '/mixed/1/x'  | '/mixed/{id}/**'   | '/base/mixed/{id}/{param1}'",
    "named and empty double star              | '/mixed/1'    | '/mixed/{id}/**'   | '/base/mixed/{id}'         ",
    "named and double star matched a slash    | '/mixed/1/'   | '/mixed/{id}/**'   | '/base/mixed/{id}/'        ",
    "wildcard before double star: fallback    | '/star/a/b'   | '/star/*/**'       | '/base/star/{param1}'      ",
    "capture rest                             | '/rest/a/b'   | '/rest/{*rest}'    | '/base/rest/{rest}'        ",
    "capture rest matched nothing: removed    | '/rest'       | '/rest/{*rest}'    | '/base/rest'               ",
    "capture rest matched a slash: slash kept | '/rest/'      | '/rest/{*rest}'    | '/base/rest/'              ",
    "functional capture rest                  | '/fn/a/b'     | '/fn/{*rest}'      | '/base/fn/{rest}'          ",
    "functional capture rest matched nothing  | '/fn'         | '/fn/{*rest}'      | '/base/fn'                 ",
    "functional capture rest matched a slash  | '/fn/'        | '/fn/{*rest}'      | '/base/fn/'                ",
    "functional double star                   | '/fns/a/b'    | '/fns/**'          | '/base/fns/{param1}'       ",
    "functional double star matched nothing   | '/fns'        | '/fns/**'          | '/base/fns'                ",
    "functional double star matched a slash   | '/fns/'       | '/fns/**'          | '/base/fns/'               "
  })
  void normalizedRoute(String path, String route, String normalized) throws Exception {
    Map<String, Object> tags = serverSpanTags(path);

    assertEquals(route, String.valueOf(tags.get(HTTP_ROUTE)));
    assertEquals(normalized, String.valueOf(tags.get(NORMALIZED_ROUTE_TAG)));
  }

  @Test
  void noNormalizedRouteWhenAppSecIsInactive() throws Exception {
    ActiveSubsystems.APPSEC_ACTIVE = false;

    Map<String, Object> tags = serverSpanTags("/regex/123");

    assertEquals("/regex/{id:\\d+}", String.valueOf(tags.get(HTTP_ROUTE)));
    assertNull(tags.get(NORMALIZED_ROUTE_TAG));
  }
}

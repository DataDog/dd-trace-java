package normalizedroute;

import static datadog.trace.api.appsec.route.NormalizedRouteHelper.NORMALIZED_ROUTE_TAG;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_ROUTE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.trace.api.config.TraceInstrumentationConfig;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * With route-based naming off the sub-resource method sets {@code http.route} again after its
 * locator, once RESTEasy stored the request match; the locator's mark still prevents the tag.
 */
@WithConfig(key = TraceInstrumentationConfig.HTTP_SERVER_ROUTE_BASED_NAMING, value = "false")
class ResteasyNormalizedRouteNoRouteNamingForkedTest extends AbstractResteasyNormalizedRouteTest {

  @Test
  void noNormalizedRouteForASubResourceLocator() throws Exception {
    Map<String, Object> tags = serverSpanTags("/locator/1/x");

    assertEquals("/{name}", String.valueOf(tags.get(HTTP_ROUTE)));
    assertNull(tags.get(NORMALIZED_ROUTE_TAG));
  }

  @Test
  void normalizedRouteForAResourceMethod() throws Exception {
    Map<String, Object> tags = serverSpanTags("/regex/123");

    assertEquals(PREFIX + "/regex/{id}", String.valueOf(tags.get(NORMALIZED_ROUTE_TAG)));
  }
}

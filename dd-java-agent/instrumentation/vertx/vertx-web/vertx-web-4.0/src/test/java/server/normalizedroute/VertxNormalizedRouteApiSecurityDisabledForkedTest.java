package server.normalizedroute;

import static datadog.trace.api.appsec.route.NormalizedRouteHelper.NORMALIZED_ROUTE_TAG;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_ROUTE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.trace.api.config.AppSecConfig;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** With API Security disabled the normalized route is not set; {@code http.route} still is. */
@WithConfig(key = AppSecConfig.API_SECURITY_ENABLED, value = "false")
class VertxNormalizedRouteApiSecurityDisabledForkedTest extends AbstractVertxNormalizedRouteTest {

  @Test
  void noNormalizedRouteWhenApiSecurityIsDisabled() throws Exception {
    assertEquals(200, get("/multi/1.json"));
    Map<String, Object> tags = serverSpanTags();

    assertEquals("/multi/:id.:format", String.valueOf(tags.get(HTTP_ROUTE)));
    assertNull(tags.get(NORMALIZED_ROUTE_TAG));
  }
}

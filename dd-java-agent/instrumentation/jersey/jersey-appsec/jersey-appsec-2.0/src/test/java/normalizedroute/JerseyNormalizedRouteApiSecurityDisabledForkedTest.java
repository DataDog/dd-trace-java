package normalizedroute;

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
class JerseyNormalizedRouteApiSecurityDisabledForkedTest extends AbstractJerseyNormalizedRouteTest {

  @Test
  void noNormalizedRouteWhenApiSecurityIsDisabled() throws Exception {
    Map<String, Object> tags = serverSpanTags("/regex/123");

    assertEquals("/regex/{id: [0-9]+}", String.valueOf(tags.get(HTTP_ROUTE)));
    assertNull(tags.get(NORMALIZED_ROUTE_TAG));
  }
}

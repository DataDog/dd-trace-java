package server.normalizedroute;

import datadog.trace.api.config.AppSecConfig;
import datadog.trace.test.junit.utils.config.WithConfig;
import org.junit.jupiter.api.Test;

/** With API Security disabled the normalized route is not set; {@code http.route} still is. */
@WithConfig(key = AppSecConfig.API_SECURITY_ENABLED, value = "false")
class VertxNormalizedRouteApiSecurityDisabledForkedTest extends AbstractVertxNormalizedRouteTest {

  @Test
  void noNormalizedRouteWhenApiSecurityIsDisabled() throws Exception {
    assertNoNormalizedRoute("/multi/1.json", "/multi/:id.:format");
  }
}

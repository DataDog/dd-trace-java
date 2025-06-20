package datadog.smoketest

import datadog.trace.api.Platform
import spock.lang.IgnoreIf

@IgnoreIf(reason = "Failing on Java 24. Skip until we have a fix.", value = {
  Platform.isJavaVersionAtLeast(24)
})
class QuarkusJBossLoggingSmokeTest extends QuarkusSmokeTest {
  @Override
  String helloEndpointName() {
    return "hello-jboss"
  }

  @Override
  String resourceName() {
    return "[datadog.smoketest.JBossLoggingResource]"
  }
}

package datadog.smoketest

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

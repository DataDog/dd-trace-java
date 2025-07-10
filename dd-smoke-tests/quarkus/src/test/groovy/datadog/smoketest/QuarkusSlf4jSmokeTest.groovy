package datadog.smoketest

class QuarkusSlf4jSmokeTest extends QuarkusSmokeTest {
  @Override
  String helloEndpointName() {
    return "hello-slf4j"
  }

  @Override
  String resourceName() {
    return "[datadog.smoketest.Slf4JResource]"
  }
}

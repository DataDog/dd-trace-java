package datadog.smoketest.springboot

/**
 * The SDK without the Datadog Java agent: the SDK polls the flag configuration from the CDN. Without
 * an API key, events are not delivered.
 */
class OpenFeatureStandaloneSmokeTest extends AbstractOpenFeatureSmokeTest {

  @Override
  ProcessBuilder createProcessBuilder() {
    final springBootShadowJar = System.getProperty("datadog.smoketest.springboot.shadowJar.path")
    final command = [javaPath()]
    command.addAll(nativeJavaProperties)
    command.addAll(['-jar', springBootShadowJar, "--server.port=${httpPort}".toString()])
    final builder = new ProcessBuilder(command).directory(new File(buildDirectory))
    builder.environment().put('DD_FEATURE_FLAGS_CONFIGURATION_SOURCE_AGENTLESS_BASE_URL', "http://localhost:${cdn.address.port}".toString())
    return builder
  }

  @Override
  boolean deliversExposures() {
    return false
  }

  @Override
  boolean testTelemetry() {
    return false
  }

  /** Disables direct intake delivery, which would otherwise reach the real Datadog intake. */
  @Override
  String apiKey() {
    return ''
  }
}

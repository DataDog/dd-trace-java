package datadog.smoketest.springboot

/**
 * The SDK with the Datadog Java agent and the default agentless configuration source: the SDK
 * polls the flag configuration from the CDN and delivers exposures through the agent.
 */
class OpenFeatureAgentlessSmokeTest extends AbstractOpenFeatureSmokeTest {

  @Override
  ProcessBuilder createProcessBuilder() {
    final springBootShadowJar = System.getProperty("datadog.smoketest.springboot.shadowJar.path")
    final command = [javaPath()]
    command.addAll(defaultJavaProperties)
    command.addAll(['-jar', springBootShadowJar, "--server.port=${httpPort}".toString()])
    final builder = new ProcessBuilder(command).directory(new File(buildDirectory))
    builder.environment().put('DD_FEATURE_FLAGS_CONFIGURATION_SOURCE_AGENTLESS_BASE_URL', "http://localhost:${cdn.address.port}".toString())
    return builder
  }

  @Override
  boolean deliversExposures() {
    return true
  }
}

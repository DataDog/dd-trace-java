package datadog.smoketest

import datadog.remoteconfig.Capabilities
import datadog.remoteconfig.Product
import datadog.smoketest.dynamicconfig.AppSecApplication
import datadog.trace.test.util.Flaky

import static datadog.trace.test.util.Predicates.ORACLE8

class AppSecActivationSmokeTest extends AbstractSmokeTest {

  @Override
  ProcessBuilder createProcessBuilder() {
    def command = [javaPath()]
    command += defaultJavaProperties.toList()
    command += [
      '-Ddd.remote_config.enabled=true',
      "-Ddd.remote_config.url=http://localhost:${server.address.port}/v0.7/config".toString(),
      '-Ddd.remote_config.poll_interval.seconds=1',
      '-Ddd.profiling.enabled=false',
      '-cp',
      System.getProperty('datadog.smoketest.shadowJar.path'),
      AppSecApplication.name
    ]

    final processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
  }

  @Flaky(value = "Telemetry product change event flakes in oracle8", condition = ORACLE8)
  void 'test activation via RC workflow'() {
    given:
    final asmRuleProducts = [Product.ASM, Product.ASM_DD, Product.ASM_DATA]

    when: 'appsec is enabled but inactive'
    final request = waitForRcClientRequest {req ->
      decodeProducts(req).find { asmRuleProducts.contains(it) } == null
    }
    final capabilities = decodeCapabilities(request)

    then: 'only ASM_ACTIVATION capability should be reported'
    assert hasCapability(capabilities, Capabilities.CAPABILITY_ASM_ACTIVATION)
    assert !hasCapability(capabilities, Capabilities.CAPABILITY_ASM_CUSTOM_RULES)

    when: 'appsec is enabled via RC'
    setRemoteConfig('datadog/2/ASM_FEATURES/asm_features_activation/config', '{"asm":{"enabled":true}}')

    then: 'we should receive a product change for appsec'
    waitForTelemetryFlat {
      final configurations = (List<Map<String, Object>>) it?.payload?.configuration ?: []
      final enabledConfig = configurations.find { it.name == 'appsec_enabled' }
      if (!enabledConfig) {
        return false
      }
      return enabledConfig.value == 'true' && enabledConfig .origin == 'remote_config'
    }

    and: 'we should have set the capabilities for ASM rules and data'
    final newRequest = waitForRcClientRequest {req ->
      decodeProducts(req).containsAll(asmRuleProducts)
    }
    final newCapabilities = decodeCapabilities(newRequest)
    assert hasCapability(newCapabilities, Capabilities.CAPABILITY_ASM_CUSTOM_RULES)
  }

  private static Set<Product> decodeProducts(final Map<String, Object> request) {
    return request.client.products.collect { Product.valueOf(it)}
  }

  private static long decodeCapabilities(final Map<String, Object> request) {
    final clientCapabilities = request.client.capabilities as byte[]
    long capabilities = 0l
    for (int i = 0; i < clientCapabilities.length; i++) {
      capabilities |= (clientCapabilities[i] & 0xFFL) << ((clientCapabilities.length - i - 1) * 8)
    }
    return capabilities
  }

  private static boolean hasCapability(final long capabilities, final long test) {
    return (capabilities & test) > 0
  }
}

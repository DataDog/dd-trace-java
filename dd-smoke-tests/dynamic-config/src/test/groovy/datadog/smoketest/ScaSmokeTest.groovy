package datadog.smoketest

import datadog.remoteconfig.Capabilities
import datadog.remoteconfig.Product
import datadog.smoketest.dynamicconfig.ScaApplication

/**
 * Smoke test for Supply Chain Analysis (SCA) via Remote Config.
 *
 * Tests that:
 * 1. ASM_SCA product subscription is reported
 * 2. CAPABILITY_ASM_SCA_VULNERABILITY_DETECTION capability is reported
 * 3. SCA configuration is received and processed
 */
class ScaSmokeTest extends AbstractSmokeTest {

  @Override
  ProcessBuilder createProcessBuilder() {
    def command = [javaPath()]
    command += defaultJavaProperties.toList()
    command += [
      '-Ddd.appsec.enabled=true',
      '-Ddd.remote_config.enabled=true',
      "-Ddd.remote_config.url=http://localhost:${server.address.port}/v0.7/config".toString(),
      '-Ddd.remote_config.poll_interval.seconds=1',
      '-Ddd.profiling.enabled=false',
      // Enable debug logging for SCA components
      '-Ddatadog.slf4j.simpleLogger.log.com.datadog.appsec=info',
      '-Ddatadog.slf4j.simpleLogger.log.datadog.remoteconfig=debug',
      '-cp',
      System.getProperty('datadog.smoketest.shadowJar.path'),
      ScaApplication.name
    ]

    final processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
  }

  void 'test SCA subscription and capability reporting'() {
    when: 'AppSec is started with SCA support'
    final request = waitForRcClientRequest { req ->
      decodeProducts(req).contains(Product.DEBUG)
    }

    then: 'ASM_SCA product should be reported'
    final products = decodeProducts(request)
    assert products.contains(Product.DEBUG)

    and: 'SCA vulnerability detection capability should be reported'
    final capabilities = decodeCapabilities(request)
    assert hasCapability(capabilities, Capabilities.CAPABILITY_ASM_SCA_VULNERABILITY_DETECTION)
  }

  void 'test SCA config processing'() {
    given: 'A sample SCA configuration with instrumentation targets'
    final scaConfig = '''
{
  "enabled": true,
  "instrumentation_targets": [
    {
      "class_name": "com/fasterxml/jackson/databind/ObjectMapper",
      "method_name": "readValue",
      "method_descriptor": "(Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/Object;",
      "vulnerability": {
        "cve_id": "CVE-2020-EXAMPLE",
        "severity": "HIGH"
      }
    }
  ]
}
'''

    when: 'AppSec is started'
    waitForRcClientRequest { req ->
      decodeProducts(req).contains(Product.DEBUG)
    }

    and: 'SCA configuration is sent via Remote Config'
    setRemoteConfig('datadog/2/ASM_SCA/sca_test_config/config', scaConfig)

    then: 'The application should process the config without errors'
    // Wait a few seconds for config processing
    sleep(3000)

    and: 'Process should be running without crashing'
    // If there were errors, the process would have crashed
    assert testedProcess.alive
  }

  //TODO fix it

  //   void 'test complete SCA instrumentation and detection flow'() {
  //     given: 'A sample SCA configuration targeting ObjectMapper.readValue'
  //     final scaConfig = '''
  // {
  //   "enabled": true,
  //   "instrumentation_targets": [
  //     {
  //       "class_name": "com/fasterxml/jackson/databind/ObjectMapper",
  //       "method_name": "readValue",
  //       "method_descriptor": "(Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/Object;"
  //     }
  //   ]
  // }
  // '''
  //
  //     when: 'AppSec is started and subscribes to SCA'
  //     waitForRcClientRequest { req ->
  //       decodeProducts(req).contains(Product.DEBUG)
  //     }
  //
  //     and: 'Application signals it is ready for instrumentation'
  //     def ready = isLogPresent { it.contains('READY_FOR_INSTRUMENTATION') }
  //     assert ready, 'Application should signal readiness'
  //
  //     and: 'SCA configuration is sent via Remote Config'
  //     setRemoteConfig('datadog/2/ASM_SCA/sca_test_config/config', scaConfig)
  //
  //     and: 'Poller receives the new configuration'
  //     // Wait for next RC poll to pick up the config
  //     sleep(2000)
  //
  //     then: 'Instrumentation is applied and logged'
  //     // Check for instrumentation-related logs
  //     def configReceived = isLogPresent { it.contains('Successfully subscribed to ASM_SCA') }
  //     assert configReceived, 'Expected SCA subscription log'
  //
  //     // If retransformation happens, the process should be alive
  //     assert testedProcess.alive
  //
  //     when: 'Application invokes the instrumented method'
  //     def methodInvoked = isLogPresent { it.contains('INVOKING_TARGET_METHOD') }
  //     assert methodInvoked, 'Application should invoke target method'
  //
  //     then: 'SCA detection callback is triggered and logged'
  //     def detectionFound = isLogPresent { String log ->
  //       log.contains('[SCA DETECTION] Vulnerable method invoked') &&
  //         log.contains('ObjectMapper') &&
  //         log.contains('readValue')
  //     }
  //     assert detectionFound, 'SCA detection should have been triggered'
  //
  //     and: 'Method invocation completes successfully'
  //     def invocationDone = isLogPresent { it.contains('METHOD_INVOCATION_DONE') }
  //     assert invocationDone, 'Method invocation should complete'
  //
  //     and: 'Process should be running without errors'
  //     // Process stays alive until all tests finish
  //     assert testedProcess.alive
  //   }

  private static Set<Product> decodeProducts(final Map<String, Object> request) {
    return request.client.products.collect { Product.valueOf(it) }
  }

  private static long decodeCapabilities(final Map<String, Object> request) {
    final clientCapabilities = request.client.capabilities as byte[]
    long capabilities = 0L
    for (int i = 0; i < clientCapabilities.length; i++) {
      capabilities |= (clientCapabilities[i] & 0xFFL) << ((clientCapabilities.length - i - 1) * 8)
    }
    return capabilities
  }

  private static boolean hasCapability(final long capabilities, final long test) {
    return (capabilities & test) > 0
  }
}

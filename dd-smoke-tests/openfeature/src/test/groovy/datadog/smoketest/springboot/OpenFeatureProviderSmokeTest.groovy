package datadog.smoketest.springboot

import datadog.remoteconfig.Capabilities
import datadog.remoteconfig.Product
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody

/** The SDK with the Datadog Java agent, receiving the flag configuration from Remote Configuration. */
class OpenFeatureProviderSmokeTest extends AbstractOpenFeatureSmokeTest {

  @Override
  ProcessBuilder createProcessBuilder() {
    setRemoteConfig("datadog/2/FFE_FLAGS/1/config", ufcPayload)

    final springBootShadowJar = System.getProperty("datadog.smoketest.springboot.shadowJar.path")
    final command = [javaPath()]
    command.addAll(defaultJavaProperties)
    command.add('-Ddd.trace.debug=true')
    command.add('-Ddd.remote_config.enabled=true')
    command.add("-Ddd.remote_config.url=http://localhost:${server.address.port}/v0.7/config".toString())
    command.addAll(['-jar', springBootShadowJar, "--server.port=${httpPort}".toString()])
    final builder = new ProcessBuilder(command).directory(new File(buildDirectory))
    builder.environment().put('DD_EXPERIMENTAL_FLAGGING_PROVIDER_ENABLED', 'true')
    builder.environment().put('DD_EXPERIMENTAL_FLAGGING_PROVIDER_SPAN_ENRICHMENT_ENABLED', 'true')
    builder.environment().put('DD_FEATURE_FLAGS_CONFIGURATION_SOURCE', 'remote_config')
    return builder
  }

  @Override
  boolean deliversExposures() {
    return true
  }

  @Override
  void publishConfiguration() {
    setRemoteConfig("datadog/2/FFE_FLAGS/1/config", ufcPayload)
  }

  void 'test first remote config poll asks agent for feature flags'() {
    when:
    final firstRcRequest = waitForRcClientRequest { req ->
      return true
    }

    then:
    firstRcRequest == rcClientMessages.first()
    // An already-running Agent gives a newly-started tracer one new-client cache bypass. If
    // FFE_FLAGS is missing here and only appears on a later poll, the Agent can miss the fast path.
    decodeProducts(firstRcRequest).find { it == Product.FFE_FLAGS } != null
    final capabilities = decodeCapabilities(firstRcRequest)
    hasCapability(capabilities, Capabilities.CAPABILITY_FFE_FLAG_CONFIGURATION_RULES)
  }

  void 'test open feature evaluation enriches the request span'() {
    setup:
    publishConfiguration()
    final request = new Request.Builder()
      .url("http://localhost:${httpPort}/openfeature/evaluate")
      .post(RequestBody.create(MediaType.parse('application/json'), JsonOutput.toJson([
        flag: 'flag-that-does-not-exist',
        variationType: 'STRING',
        defaultValue: 'fallback',
        targetingKey: 'span-enrichment-smoke-test'
      ])))
      .build()

    when:
    final response = client.newCall(request).execute()

    then:
    response.code() == 200
    new JsonSlurper().parse(response.body().byteStream()).value == 'fallback'
    waitForSpan(defaultPoll) { span ->
      span.parentId == 0 &&
        span.meta['ffe_runtime_defaults'] == '{"flag-that-does-not-exist":"fallback"}'
    }

    cleanup:
    response.close()
  }

  private static Set<Product> decodeProducts(final Map<String, Object> request) {
    return request.client.products.collect { Product.valueOf(it) }
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

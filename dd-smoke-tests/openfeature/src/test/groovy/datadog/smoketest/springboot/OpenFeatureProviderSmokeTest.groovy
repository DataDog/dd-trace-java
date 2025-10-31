package datadog.smoketest.springboot

import datadog.remoteconfig.Capabilities
import datadog.remoteconfig.Product
import datadog.smoketest.AbstractServerSmokeTest
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody
import spock.lang.Shared

class OpenFeatureProviderSmokeTest extends AbstractServerSmokeTest {

  @Shared
  private final rcPayload = JsonOutput.toJson(new JsonSlurper().parse(Thread.currentThread().contextClassLoader.getResource("ffe/flags-v1.json")))

  @Override
  ProcessBuilder createProcessBuilder() {
    setRemoteConfig("datadog/2/FFE_FLAGS/1/config", rcPayload)

    final springBootShadowJar = System.getProperty("datadog.smoketest.springboot.shadowJar.path")
    final command = [javaPath()]
    command.add('-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=5005')
    command.addAll(defaultJavaProperties)
    command.add('-Ddd.trace.debug=true')
    command.add('-Ddd.remote_config.enabled=true')
    command.add("-Ddd.remote_config.url=http://localhost:${server.address.port}/v0.7/config".toString())
    command.addAll(['-jar', springBootShadowJar, "--server.port=${httpPort}".toString()])
    final builder = new ProcessBuilder(command).directory(new File(buildDirectory))
    builder.environment().put('DD_EXPERIMENTAL_FLAGGING_PROVIDER_ENABLED', 'true')
    return builder
  }

  void 'test open feature provider metadata'() {
    setup:
    final url = "http://localhost:${httpPort}/openfeature/provider-metadata"
    final request = new Request.Builder().url(url).get().build()

    when:
    final response = client.newCall(request).execute()

    then:
    response.code() == 200
    final responseBody = new JsonSlurper().parse(response.body().byteStream())
    responseBody['metadata'] == 'datadog-openfeature-provider'
    responseBody['providerClass'] == 'datadog.trace.api.openfeature.Provider'
    responseBody != null
  }

  void 'test remote config'() {
    when:
    final rcRequest = waitForRcClientRequest {req ->
      decodeProducts(req).find {it == Product.FFE_FLAGS } != null
    }

    then:
    final capabilities = decodeCapabilities(rcRequest)
    hasCapability(capabilities, Capabilities.CAPABILITY_FFE_FLAG_CONFIGURATION_RULES)
  }

  void 'test open feature evaluation'() {
    setup:
    final url = "http://localhost:${httpPort}/openfeature/evaluate"
    final request = new Request.Builder()
      .url(url)
      .post(RequestBody.create(MediaType.parse('application/json'), JsonOutput.toJson(testCase)))
      .build()

    when:
    final response = client.newCall(request).execute()

    then:
    response.code() == 200
    final responseBody = new JsonSlurper().parse(response.body().byteStream())
    final value = responseBody.value
    assert value == testCase.result.value

    where:
    testCase << new JsonSlurper().parse(Thread.currentThread().contextClassLoader.getResource("ffe/test-cases.json"))
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

package datadog.smoketest.springboot

import datadog.remoteconfig.Capabilities
import datadog.remoteconfig.Product
import datadog.smoketest.AbstractServerSmokeTest
import groovy.json.JsonSlurper
import okhttp3.Request

class OpenFeatureProviderSmokeTest extends AbstractServerSmokeTest {

  @Override
  ProcessBuilder createProcessBuilder() {
    final springBootShadowJar = System.getProperty("datadog.smoketest.springboot.shadowJar.path")

    final command = [javaPath()]
    command.add('-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=5005')
    command.addAll(defaultJavaProperties)
    command.addAll(['-jar', springBootShadowJar, "--server.port=${httpPort}".toString()])
    final builder = new ProcessBuilder(command).directory(new File(buildDirectory))
    builder.environment().put('DD_EXPERIMENTAL_FLAGGING_PROVIDER_ENABLED', 'true')
    return builder
  }

  void setup() {
    setRemoteConfig()
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
      decodeProducts(req).find { Product.FFE_FLAGS } != null
    }

    then:
    final capabilities = decodeCapabilities(rcRequest)
    hasCapability(capabilities, Capabilities.CAPABILITY_FFE_FLAG_CONFIGURATION_RULES)
  }

  void 'test open feature evaluation'() {
    setup:
    final url = "http://localhost:${httpPort}/openfeature/${type}?flagKey=${flag}&defaultValue=${defaultValue}"
    final request = new Request.Builder()
      .url(url)
      .header('X-User-Id', user)
      .get()
      .build()

    when:
    final response = client.newCall(request).execute()

    then:
    response.code() == 200
    final responseBody = new JsonSlurper().parse(response.body().byteStream())
    responseBody['metadata'] == 'datadog-openfeature-provider'
    responseBody['providerClass'] == 'datadog.trace.api.openfeature.Provider'
    responseBody != null

    where:
    user     | type      | flag           | defaultValue
    'user_1' | 'integer' | 'int-flag'     | 23
    'user_1' | 'double'  | 'double-flag'  | 3.14D
    'user_1' | 'boolean' | 'boolean-flag' | true
    'user_1' | 'string'  | 'string-flag'  | 'defaultString'
    'user_1' | 'object'  | 'object-flag'  | [hello: 'World!']
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

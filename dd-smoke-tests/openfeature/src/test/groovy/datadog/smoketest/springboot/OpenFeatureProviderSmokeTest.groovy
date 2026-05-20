package datadog.smoketest.springboot

import datadog.remoteconfig.Capabilities
import datadog.remoteconfig.Product
import datadog.smoketest.AbstractServerSmokeTest
import datadog.trace.agent.test.server.http.TestHttpServer.HandlerApi.RequestApi
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.nio.file.Files
import java.nio.file.Paths
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody
import spock.lang.Shared
import spock.lang.Stepwise
import spock.lang.Unroll
import spock.util.concurrent.PollingConditions

/** Due to the exposure cache it's important to run the tests in the specified order */
@Stepwise
class OpenFeatureProviderSmokeTest extends AbstractServerSmokeTest {

  @Shared
  private final rcPayload = new JsonSlurper().parse(fetchResource("config/flags-v1.json")).with { json ->
    return JsonOutput.toJson(json.data.attributes)
  }

  @Override
  ProcessBuilder createProcessBuilder() {
    setRemoteConfig("datadog/2/FFE_FLAGS/1/config", rcPayload)

    final springBootShadowJar = System.getProperty("datadog.smoketest.springboot.shadowJar.path")
    final command = [javaPath()]
    command.addAll(defaultJavaProperties)
    command.add('-Ddd.trace.debug=true')
    command.add('-Ddd.remote_config.enabled=true')
    command.add("-Ddd.remote_config.url=http://localhost:${server.address.port}/v0.7/config".toString())
    command.addAll(['-jar', springBootShadowJar, "--server.port=${httpPort}".toString()])
    final builder = new ProcessBuilder(command).directory(new File(buildDirectory))
    builder.environment().put('DD_EXPERIMENTAL_FLAGGING_PROVIDER_ENABLED', 'true')
    return builder
  }

  @Override
  Closure decodedEvpProxyMessageCallback() {
    return { String path, RequestApi request ->
      if (!path.contains('api/v2/exposures')) {
        return null
      }
      return new JsonSlurper().parse(request.body)
    }
  }

  void 'test remote config'() {
    when:
    final rcRequest = waitForRcClientRequest { req ->
      decodeProducts(req).find { it == Product.FFE_FLAGS } != null
    }

    then:
    final capabilities = decodeCapabilities(rcRequest)
    hasCapability(capabilities, Capabilities.CAPABILITY_FFE_FLAG_CONFIGURATION_RULES)
  }

  void 'test open feature exposures'() {
    setup:
    setRemoteConfig("datadog/2/FFE_FLAGS/1/config", rcPayload)
    final url = "http://localhost:${httpPort}/openfeature/evaluate"
    final testCases = parseTestCases().findAll {
      it.result.flagMetadata?.doLog
    }

    when:
    final responses = testCases.collect {
      testCase ->
      final request = new Request.Builder()
      .url(url)
      .post(RequestBody.create(MediaType.parse('application/json'), JsonOutput.toJson(testCase)))
      .build()
      client.newCall(request).execute()
    }

    then:
    responses.every {
      it.code() == 200
    }
    new PollingConditions(timeout: 10).eventually {
      final requests = evpProxyMessages*.getV2() as List<Map<String, Object>>
      final events = requests*.exposures.flatten()
      assert events.size() == testCases.size()
      testCases.each {
        testCase ->
        assert events.find {
          event ->
          event.flag.key == testCase.flag && event.subject.id == testCase.targetingKey
        } != null : "Unable to find exposure with flag=${testCase.flag} and targetingKey=${testCase.targetingKey}"
      }
    }
  }

  @Unroll("test open feature evaluation [#jvmName] - #testCase.fileName[#testCase.index] - flag=#testCase.flag")
  void 'test open feature evaluation'() {
    setup:
    setRemoteConfig("datadog/2/FFE_FLAGS/1/config", rcPayload)
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
    responseBody.value == testCase.result.value
    responseBody.variant == testCase.result.variant
    responseBody.flagMetadata?.allocationKey == testCase.result.flagMetadata?.allocationKey

    where:
    testCase << parseTestCases()
    jvmName = "${System.getProperty('java.vm.vendor', 'unknown')}-${System.getProperty('java.version', 'unknown')}"
  }

  private static URL fetchResource(final String name) {
    return Thread.currentThread().getContextClassLoader().getResource(name)
  }

  private static List<Map<String, Object>> parseTestCases() {
    final folder = fetchResource('data')
    final uri = folder.toURI()
    final testsPath = Paths.get(uri)
    final files = Files.list(testsPath)
    .filter(path -> path.toString().endsWith('.json'))
    final result = []
    final slurper = new JsonSlurper()
    files.each {
      path ->
      final testCases = slurper.parse(path.toFile()) as List<Map<String, Object>>
      testCases.eachWithIndex {
        testCase, index ->
        testCase.fileName = path.fileName.toString()
        testCase.index = index
      }
      result.addAll(testCases)
    }
    return result
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

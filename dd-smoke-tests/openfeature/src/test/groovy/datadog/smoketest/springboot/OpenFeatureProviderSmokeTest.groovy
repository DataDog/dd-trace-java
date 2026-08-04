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
  private final rcConfig = new JsonSlurper().parse(fetchResource("ffe-system-test-data/ufc-config.json")) as Map<String, Object>

  @Shared
  private final rcPayload = JsonOutput.toJson(rcConfig)

  @Shared
  private final loggedAllocations = buildLoggedAllocations(rcConfig)

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
    builder.environment().put('DD_FEATURE_FLAGS_CONFIGURATION_SOURCE', 'remote_config')
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

  void 'test open feature exposures'() {
    setup:
    setRemoteConfig("datadog/2/FFE_FLAGS/1/config", rcPayload)
    final url = "http://localhost:${httpPort}/openfeature/evaluate"
    final testCases = parseTestCases()
    assert !testCases.isEmpty()

    when:
    final results = testCases.collect {
      testCase ->
      final request = new Request.Builder()
      .url(url)
      .post(RequestBody.create(MediaType.parse('application/json'), JsonOutput.toJson(testCase)))
      .build()
      final response = client.newCall(request).execute()
      final responseBody = new JsonSlurper().parse(response.body().byteStream())
      return [testCase: testCase, response: response, body: responseBody]
    }
    final expectedExposures = uniqueExpectedExposures(results)

    then:
    results.every {
      it.response.code() == 200
    }
    !expectedExposures.isEmpty()
    new PollingConditions(timeout: 10).eventually {
      final requests = evpProxyMessages*.getV2() as List<Map<String, Object>>
      final events = requests*.exposures.flatten()
      assert events.size() == expectedExposures.size()
      expectedExposures.each {
        expected ->
        assert events.find {
          event ->
          event.flag.key == expected.flag &&
          event.allocation.key == expected.allocation &&
          event.variant.key == expected.variant &&
          event.subject.id == expected.targetingKey
        } != null : "Unable to find exposure ${expected}"
      }
    }
  }

  @Unroll("test open feature evaluation - #testCase.fileName[#testCase.index] - flag=#testCase.flag")
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
    responseBody.reason == testCase.result.reason
    if (testCase.result.containsKey('errorCode')) {
      assert responseBody.errorCode == testCase.result.errorCode
    }
    if (testCase.result.containsKey('variant')) {
      assert responseBody.variant == testCase.result.variant
    }
    if (testCase.result.flagMetadata?.allocationKey) {
      assert responseBody.flagMetadata?.allocationKey == testCase.result.flagMetadata?.allocationKey
    }

    where:
    testCase << parseTestCases()
  }

  private static URL fetchResource(final String name) {
    return Thread.currentThread().getContextClassLoader().getResource(name)
  }

  private static List<Map<String, Object>> parseTestCases() {
    final folder = fetchResource('ffe-system-test-data/evaluation-cases')
    final uri = folder.toURI()
    final testsPath = Paths.get(uri)
    final files = Files.list(testsPath)
    .filter(path -> path.toString().endsWith('.json'))
    .sorted(Comparator.comparing(path -> path.fileName.toString()))
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
    assert !result.isEmpty()
    return result
  }

  private List<Map<String, String>> uniqueExpectedExposures(final List<Map<String, Object>> results) {
    final expected = []
    final seen = [] as Set<String>
    results.each { result ->
      final testCase = result.testCase as Map<String, Object>
      final body = result.body as Map<String, Object>
      final flag = testCase.flag as String
      final allocation = body.flagMetadata?.allocationKey as String
      final variant = body.variant as String
      if (!variant || !allocation || !allocationLogs(flag, allocation)) {
        return
      }

      final exposure = [
        flag: flag,
        allocation: allocation,
        variant: variant,
        targetingKey: testCase.targetingKey
      ]
      final key = "${exposure.flag}\u0000${exposure.targetingKey}\u0000${exposure.allocation}\u0000${exposure.variant}"
      if (seen.add(key)) {
        expected.add(exposure)
      }
    }
    return expected
  }

  private boolean allocationLogs(final String flag, final String allocation) {
    return loggedAllocations["${flag}\u0000${allocation}"] == true
  }

  private static Map<String, Boolean> buildLoggedAllocations(final Map<String, Object> config) {
    final logged = [:]
    (config.flags as Map<String, Object>).each { flag, definition ->
      (definition.allocations ?: []).each { allocation ->
        logged["${flag}\u0000${allocation.key}"] = allocation.doLog == true
      }
    }
    return logged
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

package datadog.smoketest.springboot

import datadog.remoteconfig.Capabilities
import datadog.remoteconfig.Product
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
class OpenFeatureProviderSmokeTest extends AbstractOpenFeatureProviderSmokeTest {

  @Shared
  private final loggedAllocations = buildLoggedAllocations(rcConfig)

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
    setRemoteConfig("datadog/2/FFE_FLAGS/1/config", rcPayload)
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
      if (definition.allocations instanceof List) {
        definition.allocations.each { allocation ->
          if (allocation instanceof Map) {
            logged["${flag}\u0000${allocation.key}"] = allocation.doLog == true
          }
        }
      }
    }
    return logged
  }
}

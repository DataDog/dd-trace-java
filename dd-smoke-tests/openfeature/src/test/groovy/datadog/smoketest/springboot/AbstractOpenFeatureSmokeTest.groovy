package datadog.smoketest.springboot

import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer

import datadog.smoketest.AbstractServerSmokeTest
import datadog.trace.agent.test.server.http.TestHttpServer
import datadog.trace.agent.test.server.http.TestHttpServer.HandlerApi.RequestApi
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.nio.file.Files
import java.nio.file.Paths
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody
import spock.lang.AutoCleanup
import spock.lang.IgnoreIf
import spock.lang.Shared
import spock.lang.Stepwise
import spock.lang.Unroll
import spock.util.concurrent.PollingConditions

/** Due to the exposure cache it's important to run the tests in the specified order */
@Stepwise
abstract class AbstractOpenFeatureSmokeTest extends AbstractServerSmokeTest {

  @Shared
  protected final ufcConfig = new JsonSlurper().parse(fetchResource("ffe-system-test-data/ufc-config.json")) as Map<String, Object>

  @Shared
  protected final ufcPayload = JsonOutput.toJson(ufcConfig)

  @Shared
  protected final loggedAllocations = buildLoggedAllocations(ufcConfig)

  /** Serves the flag configuration as the Datadog managed CDN does. */
  @Shared
  @AutoCleanup
  protected TestHttpServer cdn = httpServer {
    handlers {
      prefix("/api/v2/feature-flagging/config/rules-based/server") {
        response.status(200).send(JsonOutput.toJson([
          data: [type: 'universal-flag-configuration', attributes: ufcConfig]
        ]))
      }
    }
  }

  /** @return whether exposures are delivered through the Datadog Agent event platform proxy. */
  abstract boolean deliversExposures()

  /** Publishes the flag configuration before each evaluation. */
  void publishConfiguration() {}

  @Override
  Closure decodedTracesCallback() {
    return {}
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

  @IgnoreIf({ !instance.deliversExposures() })
  void 'test open feature exposures'() {
    setup:
    publishConfiguration()
    final testCases = parseTestCases()
    assert !testCases.isEmpty()

    when:
    final results = testCases.collect {
      testCase ->
      final response = evaluate(testCase)
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
    publishConfiguration()

    when:
    final response = evaluate(testCase)

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

  protected okhttp3.Response evaluate(final Map<String, Object> testCase) {
    final request = new Request.Builder()
    .url("http://localhost:${httpPort}/openfeature/evaluate")
    .post(RequestBody.create(MediaType.parse('application/json'), JsonOutput.toJson(testCase)))
    .build()
    return client.newCall(request).execute()
  }

  protected static URL fetchResource(final String name) {
    return Thread.currentThread().getContextClassLoader().getResource(name)
  }

  protected static List<Map<String, Object>> parseTestCases() {
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

  protected List<Map<String, String>> uniqueExpectedExposures(final List<Map<String, Object>> results) {
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

  protected boolean allocationLogs(final String flag, final String allocation) {
    return loggedAllocations["${flag}\u0000${allocation}"] == true
  }

  protected static Map<String, Boolean> buildLoggedAllocations(final Map<String, Object> config) {
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

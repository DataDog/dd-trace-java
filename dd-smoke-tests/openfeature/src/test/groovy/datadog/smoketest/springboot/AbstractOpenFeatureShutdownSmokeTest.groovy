package datadog.smoketest.springboot

import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer
import static java.util.concurrent.TimeUnit.SECONDS

import datadog.smoketest.AbstractSmokeTest
import datadog.trace.agent.test.server.http.TestHttpServer
import datadog.trace.agent.test.server.http.TestHttpServer.HandlerApi.RequestApi
import datadog.trace.agent.test.utils.OkHttpUtils
import datadog.trace.agent.test.utils.PortUtils
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.util.concurrent.PollingConditions

/** The SDK with the Datadog Java agent flushes its pending events when the application exits. */
abstract class AbstractOpenFeatureShutdownSmokeTest extends AbstractSmokeTest {

  @Shared
  protected int httpPort = PortUtils.randomOpenPort()

  protected OkHttpClient client = OkHttpUtils.client()

  @Shared
  protected final ufcConfig = new JsonSlurper().parse(fetchResource("ffe-system-test-data/ufc-config.json")) as Map<String, Object>

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

  /** @return whether the application runs with tracing enabled. */
  abstract boolean tracing()

  def setupSpec() {
    PortUtils.waitForPortToOpen(httpPort, 240, SECONDS, testedProcess)
  }

  @Override
  ProcessBuilder createProcessBuilder() {
    final springBootShadowJar = System.getProperty("datadog.smoketest.springboot.shadowJar.path")
    final command = [javaPath()]
    command.addAll(defaultJavaProperties)
    command.add("-Ddd.trace.enabled=${tracing()}".toString())
    command.addAll(['-jar', springBootShadowJar, "--server.port=${httpPort}".toString()])
    final builder = new ProcessBuilder(command).directory(new File(buildDirectory))
    builder.environment().put('DD_FEATURE_FLAGS_CONFIGURATION_SOURCE_AGENTLESS_BASE_URL', "http://localhost:${cdn.address.port}".toString())
    return builder
  }

  @Override
  boolean testTelemetry() {
    return false
  }

  @Override
  Closure decodedTracesCallback() {
    return {}
  }

  @Override
  Closure decodedEvpProxyMessageCallback() {
    return { String path, RequestApi request ->
      if (!path.contains('api/v2/flagevaluation')) {
        return null
      }
      return [
        origin: request.getHeader('DD-EVP-ORIGIN'),
        originVersion: request.getHeader('DD-EVP-ORIGIN-VERSION'),
        body: new JsonSlurper().parse(request.body)
      ]
    }
  }

  void 'test pending flag evaluations are flushed at application exit'() {
    when:
    // The first evaluation is flushed within the 10 seconds flush interval, which then restarts:
    // the next evaluation stays pending until the application exits.
    evaluate('test-case-boolean-false-assignment.json')
    new PollingConditions(timeout: 20).eventually {
      assert evaluatedFlags().contains('boolean-false-assignment')
    }
    evaluate('test-case-boolean-one-of-matches.json')

    then:
    !evaluatedFlags().contains('boolean-one-of-matches')

    when:
    testedProcess.destroy()

    then:
    testedProcess.waitFor(30, SECONDS)
    evaluatedFlags().contains('boolean-one-of-matches')
    evaluationRequests().every { it.origin == 'dd-trace-java' && it.originVersion }
  }

  protected void evaluate(final String testCaseFile) {
    final testCase = (new JsonSlurper().parse(fetchResource("ffe-system-test-data/evaluation-cases/${testCaseFile}")) as List<Map<String, Object>>).first()
    final request = new Request.Builder()
      .url("http://localhost:${httpPort}/openfeature/evaluate")
      .post(RequestBody.create(MediaType.parse('application/json'), JsonOutput.toJson(testCase)))
      .build()
    final response = client.newCall(request).execute()
    try {
      assert response.code() == 200
    } finally {
      response.close()
    }
  }

  protected List<String> evaluatedFlags() {
    return evaluationRequests()*.body*.flagEvaluations.flatten()*.flag*.key as List<String>
  }

  protected List<Map<String, Object>> evaluationRequests() {
    return evpProxyMessages*.getV2() as List<Map<String, Object>>
  }

  protected static URL fetchResource(final String name) {
    return Thread.currentThread().getContextClassLoader().getResource(name)
  }
}

package datadog.smoketest.springboot

import datadog.smoketest.AbstractIastServerSmokeTest
import okhttp3.FormBody
import okhttp3.Request

import static datadog.trace.api.config.IastConfig.IAST_DEBUG_ENABLED
import static datadog.trace.api.config.IastConfig.IAST_DETECTION_MODE
import static datadog.trace.api.config.IastConfig.IAST_ENABLED
import static datadog.trace.api.config.IastConfig.IAST_REDACTION_ENABLED

class SmokeTest extends AbstractIastServerSmokeTest {

  @Override
  ProcessBuilder createProcessBuilder() {
    String springBootShadowJar = System.getProperty('datadog.smoketest.springboot.shadowJar.path')

    List<String> command = []
    command.add(javaPath())
    //command.add("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=5005")
    command.addAll(defaultJavaProperties)
    command.addAll([
      withSystemProperty(IAST_ENABLED, true),
      withSystemProperty(IAST_DETECTION_MODE, 'FULL'),
      withSystemProperty(IAST_DEBUG_ENABLED, true),
      withSystemProperty(IAST_REDACTION_ENABLED, false)
    ])
    command.addAll((String[]) ['-jar', springBootShadowJar, "--server.port=${httpPort}"])
    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
    // Spring will print all environment variables to the log, which may pollute it and affect log assertions.
    processBuilder.environment().clear()
    return processBuilder
  }

  void 'ssrf is present'() {
    setup:
    final expected = 'https://dd.datad0g.com/test'
    final url = "http://localhost:${httpPort}/ssrf/execute"
    final body = new FormBody.Builder()
    .add('url', expected)
    .add('client', suite.clientImplementation)
    .add('method', suite.executedMethod)
    .add('requestType', suite.requestType)
    .build()
    final request = new Request.Builder().url(url).post(body).build()

    when:
    def response = client.newCall(request).execute()

    then:
    response.successful
    hasVulnerability {
      vul ->
      vul.type == 'SSRF'
      && vul.location.path == 'datadog.smoketest.springboot.SsrfController'
      && vul.location.line == suite.expectedLine
      && vul.evidence.valueParts[0].value == expected
    }


    where:
    suite << createTestSuite()
  }

  private Iterable<TestSuite> createTestSuite() {
    final result = []
    for (SsrfController.Client client : SsrfController.Client.values()) {
      for (SsrfController.ExecuteMethod method : SsrfController.ExecuteMethod.values()) {
        if (method.name().startsWith('HOST')) {
          result.add(createTestSuite(client, method, SsrfController.Request.HttpRequest))
        }
        result.add(createTestSuite(client, method, SsrfController.Request.HttpUriRequest))
      }
    }
    return result as Iterable<TestSuite>
  }

  private TestSuite createTestSuite(client, method, request) {
    return new TestSuite(
    description: "ssrf is present for ${client} client and ${method} method with ${request}",
    executedMethod: method.name(),
    clientImplementation: client.name(),
    expectedLine: method.expectedLine,
    requestType: request.name()
    )
  }

  private static class TestSuite {
    String description
    String executedMethod
    String clientImplementation
    Integer expectedLine
    String requestType

    @Override
    String toString() {
      return "IAST apache httpclient 4 test suite: ${description}"
    }
  }
}

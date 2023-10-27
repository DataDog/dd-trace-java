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
    def expected = 'http://dd.datad0g.com/test/'+suite.executedMethod
    final url = "http://localhost:${httpPort}/ssrf/execute"
    final body = new FormBody.Builder()
    .add('url', expected)
    .add('client', suite.clientImplementation)
    .add('method', suite.executedMethod)
    .add('requestType', suite.requestType)
    .add('scheme', suite.scheme)
    .build()
    final request = new Request.Builder().url(url).post(body).build()
    if(suite.scheme == 'https') {
      expected = expected.replace('http', 'https')
    }

    when:
    def response = client.newCall(request).execute()

    then:
    response.successful
    hasVulnerability {
      vul ->
      vul.type == 'SSRF'
      && vul.location.path == 'datadog.smoketest.springboot.SsrfController'
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
          result.add(createTestSuite(client, method, SsrfController.Request.HttpRequest, 'http'))
          result.add(createTestSuite(client, method, SsrfController.Request.HttpRequest, 'https'))
        }
        result.add(createTestSuite(client, method, SsrfController.Request.HttpUriRequest, 'http'))
      }
    }
    return result as Iterable<TestSuite>
  }

  private TestSuite createTestSuite(client, method, request, scheme) {
    return new TestSuite(
    description: "ssrf is present for ${client} client and ${method} method with ${request} and ${scheme} scheme",
    executedMethod: method.name(),
    clientImplementation: client.name(),
    requestType: request.name(),
    scheme: scheme
    )
  }

  private static class TestSuite {
    String description
    String executedMethod
    String clientImplementation
    String requestType
    String scheme

    @Override
    String toString() {
      return "IAST apache httpclient 4 test suite: ${description}"
    }
  }
}

package datadog.smoketest.springboot

import datadog.smoketest.AbstractIastServerSmokeTest
import okhttp3.FormBody
import okhttp3.Request

import static datadog.smoketest.springboot.SsrfController.ExecuteMethod.*
import static datadog.trace.api.config.IastConfig.IAST_DEBUG_ENABLED
import static datadog.trace.api.config.IastConfig.IAST_DETECTION_MODE
import static datadog.trace.api.config.IastConfig.IAST_ENABLED
import static datadog.trace.api.config.IastConfig.IAST_REDACTION_ENABLED

class smokeTest extends AbstractIastServerSmokeTest {

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
    final url = "http://localhost:${httpPort}/ssrf/execute"
    final body = new FormBody.Builder().add('url', 'https://dd.datad0g.com/').add('method', executeMethod.name()).build()
    final request = new Request.Builder().url(url).post(body).build()

    when:
    def response = client.newCall(request).execute()

    then:
    response.successful
    hasVulnerability { vul -> vul.type == 'SSRF' && vul.location.path == 'datadog.smoketest.springboot.SsrfController' && vul.location.line == line }

    where:
    executeMethod | line
    REQUEST | 39
    REQUEST_CONTEXT | 42
    HOST_REQUEST | 45
    REQUEST_HANDLER | 48
    REQUEST_HANDLER_CONTEXT | 51
    HOST_REQUEST_HANDLER | 54
    HOST_REQUEST_HANDLER_CONTEXT | 57
    HOST_REQUEST_CONTEXT | 60
  }
}

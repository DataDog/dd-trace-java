package datadog.smoketest.springboot

import datadog.smoketest.AbstractIastServerSmokeTest
import okhttp3.Request
import okhttp3.Response

import static datadog.trace.api.config.IastConfig.IAST_DEBUG_ENABLED
import static datadog.trace.api.config.IastConfig.IAST_DETECTION_MODE
import static datadog.trace.api.config.IastConfig.IAST_ENABLED

class IastSpringBootSmokeTest extends AbstractIastServerSmokeTest {

  @Override
  ProcessBuilder createProcessBuilder() {
    String springBootWar = System.getProperty('datadog.smoketest.springboot.war.path')

    List<String> command = []
    command.add(javaPath())
    command.addAll(defaultJavaProperties)
    command.addAll([
      withSystemProperty(IAST_ENABLED, true),
      withSystemProperty(IAST_DETECTION_MODE, 'FULL'),
      withSystemProperty(IAST_DEBUG_ENABLED, true)
    ])
    command.addAll((String[]) ['-jar', springBootWar, "--server.port=${httpPort}"])
    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
    // Spring will print all environment variables to the log, which may pollute it and affect log assertions.
    processBuilder.environment().clear()
    return processBuilder
  }

  void 'find xss in jsp'() {
    given:
    String url = "http://localhost:${httpPort}/test_xss_in_jsp?test=thisCouldBeDangerous"

    when:
    Response response = client.newCall(new Request.Builder().url(url).get().build()).execute()

    then:
    response.successful
    hasVulnerability { vul ->
      vul.type == 'XSS'
    }
  }
}

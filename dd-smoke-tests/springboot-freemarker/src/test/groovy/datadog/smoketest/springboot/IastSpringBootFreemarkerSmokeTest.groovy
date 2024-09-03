package datadog.smoketest.springboot

import datadog.smoketest.AbstractIastServerSmokeTest
import okhttp3.Request

import static datadog.trace.api.config.IastConfig.IAST_DEBUG_ENABLED
import static datadog.trace.api.config.IastConfig.IAST_DETECTION_MODE
import static datadog.trace.api.config.IastConfig.IAST_ENABLED
import static datadog.trace.api.config.IastConfig.IAST_REDACTION_ENABLED

class IastSpringBootFreemarkerSmokeTest extends AbstractIastServerSmokeTest {

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

  void 'xss is present'() {
    setup:
    final url = "http://localhost:${httpPort}/xss/freemarker?name=${param}&templateName=${templateName}"
    final request = new Request.Builder().url(url).get().build()

    when:
    client.newCall(request).execute()

    then:
    hasVulnerability { vul -> vul.type == 'XSS' && vul.location.path == templateName && vul.location.line == line }

    where:
    param  | templateName                      | line
    'name' | 'freemarker-2.3.24-insecure.ftlh' | 9
    'name' | 'freemarker-2.3.9-insecure.ftlh'  | 6
  }

  void 'xss is not present'() {
    setup:
    final url = "http://localhost:${httpPort}/xss/freemarker?name=${param}&templateName=${templateName}"
    final request = new Request.Builder().url(url).get().build()

    when:
    client.newCall(request).execute()

    then:
    noVulnerability { vul -> vul.type == 'XSS' && vul.location.path == templateName && vul.location.line == line }

    where:
    param  | templateName                    | line
    'name' | 'freemarker-2.3.24-secure.ftlh' | 9
    'name' | 'freemarker-2.3.9-secure.ftlh'  | 6
  }
}

package datadog.smoketest

import okhttp3.Request

import static datadog.trace.api.config.IastConfig.IAST_DEBUG_ENABLED
import static datadog.trace.api.config.IastConfig.IAST_DETECTION_MODE
import static datadog.trace.api.config.IastConfig.IAST_ENABLED
import static datadog.trace.api.config.IastConfig.IAST_REDACTION_ENABLED

class AbstractIastGrailsSmokeTest extends AbstractIastServerSmokeTest {

  @Override
  ProcessBuilder createProcessBuilder() {
    final shadowJar = System.getProperty('datadog.smoketest.grails.shadowJar.path')
    final command = []
    command.add(javaPath())
    command.addAll(defaultJavaProperties)
    command.addAll([
      withSystemProperty(IAST_ENABLED, true),
      withSystemProperty(IAST_DETECTION_MODE, 'FULL'),
      withSystemProperty(IAST_DEBUG_ENABLED, true),
      withSystemProperty(IAST_REDACTION_ENABLED, false)
    ])
    command.addAll(["-Dserver.port=${httpPort}", '-jar', shadowJar] as String[])
    final processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
    // Spring will print all environment variables to the log, which may pollute it and affect log assertions.
    processBuilder.environment().clear()
    return processBuilder
  }

  void 'weak hash vulnerability is present'() {
    setup:
    String url = "http://localhost:${httpPort}/iast/weakHash?algorithm=MD5"
    def request = new Request.Builder().url(url).get().build()

    when:
    client.newCall(request).execute()

    then:
    hasVulnerability { vul ->
      vul.type == 'WEAK_HASH' &&
        vul.evidence.value == 'MD5'
    }
  }
}

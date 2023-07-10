import datadog.smoketest.AbstractIastVertxSmokeTest
import groovy.transform.CompileDynamic
import okhttp3.Request

import static datadog.trace.api.config.IastConfig.IAST_DEBUG_ENABLED
import static datadog.trace.api.config.IastConfig.IAST_DETECTION_MODE
import static datadog.trace.api.config.IastConfig.IAST_ENABLED

@CompileDynamic
class IastVertxSmokeTest extends AbstractIastVertxSmokeTest {

  void 'test unvalidated redirect using redirect'() {
    given:
    final url = "http://localhost:${httpPort}/unvalidatedredirect1?path=redirected"
    final request = new Request.Builder().url(url).get().build()

    when:
    client.newCall(request).execute()

    then:
    hasVulnerability { vul ->
      vul.type == 'UNVALIDATED_REDIRECT'
    }
  }

  void 'test unvalidated redirect using redirect with handler'() {
    given:
    final url = "http://localhost:${httpPort}/unvalidatedredirect2?path=redirected"
    final request = new Request.Builder().url(url).get().build()

    when:
    client.newCall(request).execute()

    then:
    hasVulnerability { vul ->
      vul.type == 'UNVALIDATED_REDIRECT'
    }
  }

  @Override
  ProcessBuilder createProcessBuilder() {
    final vertxUberJar = System.getProperty('datadog.smoketest.vertx.uberJar.path')
    final command = []
    command.add(javaPath())
    command.addAll(defaultJavaProperties)
    command.addAll((String[]) [
      //'-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=5005',
      withSystemProperty(IAST_ENABLED, true),
      withSystemProperty(IAST_DETECTION_MODE, 'FULL'),
      withSystemProperty(IAST_DEBUG_ENABLED, true),
      '-Ddd.app.customlogmanager=true',
      "-Dvertx.http.port=${httpPort}",
      '-cp',
      vertxUberJar,
      'datadog.vertx_4_2.IastVerticle'
    ])
    final processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
    return processBuilder
  }
}

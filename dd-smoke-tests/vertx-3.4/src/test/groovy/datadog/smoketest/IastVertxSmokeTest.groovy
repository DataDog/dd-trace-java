package datadog.smoketest

import groovy.transform.CompileDynamic
import okhttp3.Request
import spock.lang.IgnoreIf

import static datadog.trace.api.config.IastConfig.*

@CompileDynamic
@IgnoreIf({
  // TODO https://github.com/eclipse-vertx/vert.x/issues/2172
  new BigDecimal(System.getProperty("java.specification.version")).isAtLeast(17.0)
})
class IastVertxSmokeTest extends AbstractIastVertxSmokeTest {

  void 'test insecure cookie set using putHeader'() {
    setup:
    final url = "http://localhost:${httpPort}/insecurecookieheader"
    final request = new Request.Builder().url(url).get().build()

    when:
    def response = client.newCall(request).execute()

    then:
    response.isSuccessful()
    response.headers().toString().contains("user-id")
    processTestLogLines { String log ->
      log.contains("INSECURE_COOKIE") && log.contains("user-id")
    }
  }

  void 'test insecure cookie set using putHeader with Iterator'() {
    setup:
    final url = "http://localhost:${httpPort}/insecurecookieheader2"
    final request = new Request.Builder().url(url).get().build()

    when:
    def response = client.newCall(request).execute()

    then:
    response.isSuccessful()
    response.headers().toString().contains("user-id")
    response.headers().toString().contains("firstcookie")
    processTestLogLines { String log ->
      log.contains("INSECURE_COOKIE") && log.contains("firstcookie")
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
      'datadog.vertx_3_4.IastVerticle'
    ])
    final processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
    return processBuilder
  }
}

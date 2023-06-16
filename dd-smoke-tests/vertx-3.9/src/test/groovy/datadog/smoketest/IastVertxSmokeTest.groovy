package datadog.smoketest


import groovy.transform.CompileDynamic
import okhttp3.Request

import static datadog.trace.api.config.IastConfig.*

@CompileDynamic
class IastVertxSmokeTest extends AbstractIastVertxSmokeTest {


  void 'test insecure cookie'() {
    setup:
    final url = "http://localhost:${httpPort}/insecurecookie"
    final request = new Request.Builder().url(url).get().build()

    when:
    def response = client.newCall(request).execute()

    then:
    response.isSuccessful()
    response.headers().toString().contains("userA-id")
    hasVulnerability { vul ->
      vul.type == 'INSECURE_COOKIE' &&
        vul.evidence.value == 'userA-id'
    }
  }


  void 'test insecure cookie set using putHeader'() {
    setup:
    final url = "http://localhost:${httpPort}/insecurecookieheader"
    final request = new Request.Builder().url(url).get().build()

    when:
    def response = client.newCall(request).execute()

    then:
    response.isSuccessful()
    response.headers().toString().contains("user-id")
    hasVulnerability { vul ->
      vul.type == 'INSECURE_COOKIE' &&
        vul.evidence.value == 'user-id'
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
    hasVulnerability { vul ->
      vul.type == 'INSECURE_COOKIE' &&
        vul.evidence.value == 'firstcookie'
    }
  }

  void 'test insecure cookie set using putHeader'() {
    setup:
    final url = "http://localhost:${httpPort}/insecurecookieheader"
    final request = new Request.Builder().url(url).get().build()

    when:
    def response = client.newCall(request).execute()

    then:
    response.isSuccessful()
    response.headers().toString().contains("user-id")
    hasVulnerability { vul ->
      vul.type == 'INSECURE_COOKIE' &&
        vul.evidence.value == 'user-id'
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
    hasVulnerability { vul ->
      vul.type == 'INSECURE_COOKIE' &&
        vul.evidence.value == 'firstcookie'
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
      'datadog.vertx_3_9.IastVerticle'
    ])
    final processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
    return processBuilder
  }
}

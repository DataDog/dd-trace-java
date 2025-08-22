package datadog.smoketest

import groovy.json.JsonSlurper
import okhttp3.Request

import static datadog.trace.api.config.IastConfig.IAST_DEBUG_ENABLED
import static datadog.trace.api.config.IastConfig.IAST_DETECTION_MODE
import static datadog.trace.api.config.IastConfig.IAST_ENABLED
import static org.junit.jupiter.api.Assumptions.assumeTrue

class IastPropagationSmokeTest extends AbstractIastServerSmokeTest {

  @Override
  ProcessBuilder createProcessBuilder() {
    final jarPath = System.getProperty('datadog.smoketest.springboot.shadowJar.path')
    List<String> command = []
    command.add(javaPath())
    command.addAll(defaultJavaProperties)
    command.addAll((String[]) [
      withSystemProperty(IAST_ENABLED, true),
      withSystemProperty(IAST_DETECTION_MODE, 'FULL'),
      withSystemProperty(IAST_DEBUG_ENABLED, true),
      '-jar',
      jarPath,
      "--server.port=${httpPort}"
    ])
    final processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
    return processBuilder
  }

  void 'test propagation language=#language, method=#method'() {
    setup:
    // TODO fix when we have groovy default string propagation
    assumeTrue(language != 'groovy')
    String param = "${language}_${method}"
    String url = "http://localhost:${httpPort}/${language}/${method}?param=$param"

    when:
    def request = new Request.Builder().url(url).get().build()
    def response = client.newCall(request).execute()

    then:
    def responseBodyStr = response.body().string()
    response.code() == 200
    hasTainted { tainted ->
      tainted.value == responseBodyStr &&
        tainted.ranges[0].source.origin == 'http.request.parameter'
    }

    where:
    [language, method] << methods()
  }

  private List<List<String>> methods() {
    final languages = ['java', 'scala', 'groovy', 'kotlin']
    return languages.collectMany { language ->
      final methods = new JsonSlurper().parse(new URL("http://localhost:${httpPort}/${language}")) as List<String>
      return methods.collect { method -> [language, method] }
    }
  }
}

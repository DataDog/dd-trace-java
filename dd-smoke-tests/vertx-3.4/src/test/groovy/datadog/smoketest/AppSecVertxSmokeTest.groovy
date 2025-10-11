package datadog.smoketest

import datadog.smoketest.appsec.AbstractAppSecServerSmokeTest
import datadog.trace.agent.test.utils.OkHttpUtils
import okhttp3.Request
import okhttp3.Response
import spock.lang.IgnoreIf

@IgnoreIf({
  // TODO https://github.com/eclipse-vertx/vert.x/issues/2172
  new BigDecimal(System.getProperty("java.specification.version")).isAtLeast(17.0)
})
class AppSecVertxSmokeTest extends AbstractAppSecServerSmokeTest {

  @Override
  def logLevel() {
    'DEBUG'
  }

  @Override
  ProcessBuilder createProcessBuilder() {
    String vertxUberJar = System.getProperty("datadog.smoketest.vertx.uberJar.path")

    List<String> command = new ArrayList<>()
    command.add(javaPath())
    command.addAll(defaultJavaProperties)
    command.addAll(defaultAppSecProperties)
    command.addAll((String[]) [
      "-Ddd.writer.type=MultiWriter:TraceStructureWriter:${output.getAbsolutePath()},DDAgentWriter",
      "-Dvertx.http.port=${httpPort}",
      "-jar",
      vertxUberJar
    ])
    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
  }

  @Override
  File createTemporaryFile() {
    return new File("${buildDirectory}/tmp/trace-structure-vertx.out")
  }

  void 'API Security samples only one request per endpoint'() {
    given:
    def url = "http://localhost:${httpPort}/api_security/sampling/200?test=value"
    def client = OkHttpUtils.clientBuilder().build()
    def request = new Request.Builder()
      .url(url)
      .addHeader('X-My-Header', "value")
      .get()
      .build()

    when:
    List<Response> responses = (1..3).collect {
      client.newCall(request).execute()
    }

    then:
    responses.each {
      assert it.code() == 200
    }
    waitForTraceCount(3)
    def spans = rootSpans.toList().toSorted { it.span.duration }
    spans.size() == 3
    def sampledSpans = spans.findAll {
      it.meta.keySet().any {
        it.startsWith('_dd.appsec.s.req.')
      }
    }
    sampledSpans.size() == 1
    def span = sampledSpans[0]
    span.meta.containsKey('_dd.appsec.s.req.query')
    span.meta.containsKey('_dd.appsec.s.req.params')
    span.meta.containsKey('_dd.appsec.s.req.headers')
  }
}

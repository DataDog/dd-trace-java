import datadog.smoketest.appsec.AbstractAppSecServerSmokeTest
import datadog.trace.agent.test.utils.OkHttpUtils
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import spock.lang.IgnoreIf

import java.util.zip.GZIPInputStream

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

  void 'test response schema extraction'() {
    given:
    def url = "http://localhost:${httpPort}/api_security/response"
    def client = OkHttpUtils.clientBuilder().build()
    def body = [
      "main"    : [["key": "id001", "value": 1345.67], ["value": 1567.89, "key": "id002"]],
      "nullable": null,
    ]
    def request = new Request.Builder()
      .url(url)
      .post(RequestBody.create(MediaType.get('application/json'), JsonOutput.toJson(body)))
      .build()

    when:
    final response = client.newCall(request).execute()
    waitForTraceCount(1)

    then:
    response.code() == 200
    def span = rootSpans.first()
    span.meta.containsKey('_dd.appsec.s.res.headers')
    span.meta.containsKey('_dd.appsec.s.res.body')
    final schema = new JsonSlurper().parse(unzip(span.meta.get('_dd.appsec.s.res.body')))
    assert schema == [["main": [[[["key": [8], "value": [16]]]], ["len": 2]], "nullable": [1]]]
  }


  private static byte[] unzip(final String text) {
    final inflaterStream = new GZIPInputStream(new ByteArrayInputStream(text.decodeBase64()))
    return inflaterStream.getBytes()
  }
}

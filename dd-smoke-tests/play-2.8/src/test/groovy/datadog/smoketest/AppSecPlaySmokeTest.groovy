package datadog.smoketest

import datadog.smoketest.appsec.AbstractAppSecServerSmokeTest
import datadog.trace.agent.test.utils.OkHttpUtils
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import spock.lang.Shared

import java.nio.file.Files
import java.util.zip.GZIPInputStream

import static java.util.concurrent.TimeUnit.SECONDS

abstract class AppSecPlaySmokeTest extends AbstractAppSecServerSmokeTest {

  @Shared
  File playDirectory = new File("${buildDirectory}/stage/main")

  @Override
  ProcessBuilder createProcessBuilder() {
    // If the server is not shut down correctly, this file can be left there and will block
    // the start of a new test
    def runningPid = new File(playDirectory.getPath(), "RUNNING_PID")
    if (runningPid.exists()) {
      runningPid.delete()
    }
    def command = isWindows() ? 'main.bat' : 'main'
    ProcessBuilder processBuilder =
      new ProcessBuilder("${playDirectory}/bin/${command}")
    processBuilder.directory(playDirectory)
    processBuilder.environment().put("JAVA_OPTS",
      (defaultAppSecProperties + defaultJavaProperties).collect({ it.replace(' ', '\\ ')}).join(" ")
      + " -Dconfig.file=${playDirectory}/conf/application.conf"
      + " -Dhttp.port=${httpPort}"
      + " -Dhttp.address=127.0.0.1"
      + " -Dplay.server.provider=${serverProvider()}"
      + " -Ddd.writer.type=MultiWriter:TraceStructureWriter:${output.getAbsolutePath()},DDAgentWriter")
    return processBuilder
  }

  @Override
  File createTemporaryFile() {
    new File("${buildDirectory}/tmp/trace-structure-play-2.8-appsec-${serverProviderName()}.out")
  }

  abstract String serverProviderName()

  abstract String serverProvider()

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

  // Ensure to clean up server and not only the shell script that starts it
  def cleanupSpec() {
    def pid = runningServerPid()
    if (pid) {
      def commands = isWindows() ? ['taskkill', '/PID', pid, '/T', '/F'] : ['kill', '-9', pid]
      new ProcessBuilder(commands).start().waitFor(10, SECONDS)
    }
  }

  def runningServerPid() {
    def runningPid = new File(playDirectory.getPath(), 'RUNNING_PID')
    if (runningPid.exists()) {
      return Files.lines(runningPid.toPath()).findAny().orElse(null)
    }
  }

  static isWindows() {
    return System.getProperty('os.name').toLowerCase().contains('win')
  }

  static class Akka extends AppSecPlaySmokeTest {

    @Override
    String serverProviderName() {
      return "akka-http"
    }

    @Override
    String serverProvider() {
      return "play.core.server.AkkaHttpServerProvider"
    }
  }

  static class Netty extends AppSecPlaySmokeTest {
    @Override
    String serverProviderName() {
      return "netty"
    }

    @Override
    String serverProvider() {
      return "play.core.server.NettyServerProvider"
    }
  }
}

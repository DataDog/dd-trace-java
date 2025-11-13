package datadog.smoketest

import datadog.smoketest.appsec.AbstractAppSecServerSmokeTest
import datadog.trace.agent.test.utils.OkHttpUtils
import okhttp3.Request
import okhttp3.Response
import spock.lang.Shared

import java.nio.file.Files

import static java.util.concurrent.TimeUnit.SECONDS

class AppSecPlayNettySmokeTest  extends AbstractAppSecServerSmokeTest {

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
    ProcessBuilder processBuilder = new ProcessBuilder("${playDirectory}/bin/${command}")
    processBuilder.directory(playDirectory)
    processBuilder.environment().put("JAVA_OPTS",
      (defaultAppSecProperties + defaultJavaProperties).collect({ it.replace(' ', '\\ ')}).join(" ")
      + " -Dconfig.file=${playDirectory}/conf/application.conf"
      + " -Dhttp.port=${httpPort}"
      + " -Dhttp.address=127.0.0.1"
      + " -Dplay.server.provider=play.core.server.NettyServerProvider"
      + " -Ddd.writer.type=MultiWriter:TraceStructureWriter:${output.getAbsolutePath()},DDAgentWriter")
    return processBuilder
  }

  @Override
  File createTemporaryFile() {
    return new File("${buildDirectory}/tmp/trace-structure-play-2.4-appsec-netty.out")
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
    span.meta.containsKey('_dd.appsec.s.req.headers')
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
}
